package com.caglar.secure_ticketing_api.idempotency.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.caglar.secure_ticketing_api.common.error.ApiErrorResponseWriter;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;
import com.caglar.secure_ticketing_api.idempotency.service.IdempotencyClaim;
import com.caglar.secure_ticketing_api.idempotency.service.IdempotencyService;
import com.caglar.secure_ticketing_api.idempotency.service.ReplayRenderer;
import com.caglar.secure_ticketing_api.idempotency.service.RequestFingerprint;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


@Component
public class IdempotencyFilter extends OncePerRequestFilter {

	static final String KEY_HEADER = "Idempotency-Key";
	static final String REPLAY_HEADER = "X-Idempotent-Replay";

	private static final int MAX_KEY_LENGTH = 100;

	private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

	private final IdempotencyService idempotency;
	private final RequestFingerprint fingerprint;
	private final ApiErrorResponseWriter errorWriter;
	private final List<GuardedEndpoint> guarded;

	IdempotencyFilter(IdempotencyService idempotency, RequestFingerprint fingerprint,
			ApiErrorResponseWriter errorWriter, List<ReplayRenderer> renderers) {

		this.idempotency = idempotency;
		this.fingerprint = fingerprint;
		this.errorWriter = errorWriter;
		this.guarded = renderers.stream().map(GuardedEndpoint::of).toList();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		GuardedEndpoint endpoint = match(request);
		if (endpoint == null) {
			chain.doFilter(request, response);
			return;
		}

		Long userId = authenticatedUserId();
		if (userId == null) {
			
			chain.doFilter(request, response);
			return;
		}

		String key = request.getHeader(KEY_HEADER);
		if (key == null) {
			errorWriter.write(request, response, ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
					"This endpoint requires an %s header. Use a UUID.".formatted(KEY_HEADER));
			return;
		}
		
		if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
			errorWriter.write(request, response, ErrorCode.IDEMPOTENCY_KEY_INVALID,
					"An %s must be non-blank and at most %d characters."
							.formatted(KEY_HEADER, MAX_KEY_LENGTH));
			return;
		}

		byte[] body = request.getInputStream().readAllBytes();
		String requestHash = fingerprint.forRequest(
				request.getMethod(), request.getRequestURI(), body);

		IdempotencyClaim claim = idempotency.claim(userId, key, endpoint.descriptor(), requestHash);

		switch (claim) {
			case IdempotencyClaim.Claimed(Long recordId) ->
					execute(new CachedBodyHttpServletRequest(request, body), response, chain,
							endpoint, recordId);

			case IdempotencyClaim.Replay(Long resourceId, String responseHash) ->
					replay(request, response, endpoint, resourceId, responseHash);

			case IdempotencyClaim.Mismatch() ->
					errorWriter.write(request, response, ErrorCode.IDEMPOTENCY_KEY_REUSED,
							"This %s was already used with a different request.".formatted(KEY_HEADER));

			case IdempotencyClaim.InProgress() ->
					errorWriter.write(request, response, ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
							"An earlier request with this %s is still running. Retry shortly."
									.formatted(KEY_HEADER));
		}
	}

	private void execute(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
			GuardedEndpoint endpoint, Long recordId) throws ServletException, IOException {

		ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
		boolean recorded = false;
		try {
			chain.doFilter(request, wrapper);
			recorded = record(endpoint, recordId, wrapper);
		}
		finally {
			if (!recorded) {
				idempotency.discard(recordId);
			}
			wrapper.copyBodyToResponse();
		}
	}

	private boolean record(GuardedEndpoint endpoint, Long recordId,
			ContentCachingResponseWrapper wrapper) {

		int status = wrapper.getStatus();
		if (status < 200 || status > 299) {
			return false;
		}

		byte[] body = wrapper.getContentAsByteArray();
		Long resourceId = endpoint.renderer().extractResourceId(body);
		if (resourceId == null) {
			log.warn("{} returned {} but no resource id could be read; the key will not be replayable",
					endpoint.descriptor(), status);
			return false;
		}

		idempotency.complete(recordId, resourceId, fingerprint.forResponse(body));
		return true;
	}

	private void replay(HttpServletRequest request, HttpServletResponse response,
			GuardedEndpoint endpoint, Long resourceId, String responseHash) throws IOException {

		ReplayRenderer.RenderedResponse rendered = endpoint.renderer().render(resourceId);
		if (rendered == null) {
			errorWriter.write(request, response, ErrorCode.NOT_FOUND,
					"The resource created by this %s no longer exists.".formatted(KEY_HEADER));
			return;
		}

		byte[] body = rendered.body().getBytes(StandardCharsets.UTF_8);
		if (!fingerprint.forResponse(body).equals(responseHash)) {
			log.info("Resource {} has changed since the first response for this key", resourceId);
		}

		response.setStatus(rendered.status());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setHeader(REPLAY_HEADER, "true");
		response.getOutputStream().write(body);
	}

	private GuardedEndpoint match(HttpServletRequest request) {
		PathContainer path = PathContainer.parsePath(request.getRequestURI());
		return guarded.stream()
				.filter(endpoint -> endpoint.matches(request.getMethod(), path))
				.findFirst()
				.orElse(null);
	}

	private Long authenticatedUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			return null;
		}
		try {
			return Long.valueOf(String.valueOf(authentication.getPrincipal()));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private record GuardedEndpoint(String method, PathPattern pattern, String descriptor,
			ReplayRenderer renderer) {

		static GuardedEndpoint of(ReplayRenderer renderer) {
			String[] parts = renderer.endpoint().split(" ", 2);
			if (parts.length != 2) {
				throw new IllegalArgumentException(
						"A renderer endpoint must read '<METHOD> <path pattern>', got: "
								+ renderer.endpoint());
			}
			return new GuardedEndpoint(parts[0], PathPatternParser.defaultInstance.parse(parts[1]),
					renderer.endpoint(), renderer);
		}

		boolean matches(String requestMethod, PathContainer path) {
			return this.method.equalsIgnoreCase(requestMethod) && this.pattern.matches(path);
		}
	}
}
