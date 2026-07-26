package com.caglar.secure_ticketing_api.common.ratelimit;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.caglar.secure_ticketing_api.common.error.ApiErrorResponseWriter;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;
import com.caglar.secure_ticketing_api.common.security.AuthenticatedActor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


@Component
public class RateLimitFilter extends OncePerRequestFilter {

	private static final String LIMIT_HEADER = "X-RateLimit-Limit";
	private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
	private static final String RESET_HEADER = "X-RateLimit-Reset";
	private static final String DRAFT_HEADER = "RateLimit";

	private final RateLimiter rateLimiter;
	private final RateLimitProperties properties;
	private final AuthenticatedActor actor;
	private final ApiErrorResponseWriter errorWriter;
	private final List<LimitedRoute> routes;

	RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties,
			AuthenticatedActor actor, ApiErrorResponseWriter errorWriter) {

		this.rateLimiter = rateLimiter;
		this.properties = properties;
		this.actor = actor;
		this.errorWriter = errorWriter;
		this.routes = List.of(
				LimitedRoute.of("POST", "/api/auth/register", properties.authPolicy(), Scope.CLIENT),
				LimitedRoute.of("POST", "/api/auth/login", properties.authPolicy(), Scope.CLIENT),
				LimitedRoute.of("POST", "/api/auth/refresh", properties.authPolicy(), Scope.CLIENT),
				LimitedRoute.of("POST", "/api/events/{eventId}/reservations",
						properties.reservationPolicy(), Scope.PRINCIPAL));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		LimitedRoute route = match(request);
		if (route == null) {
			chain.doFilter(request, response);
			return;
		}

		RateLimitPolicy policy = route.policy();
		RateLimiter.Verdict verdict = rateLimiter.tryConsume(keyFor(route, request), policy);

		writeLimitHeaders(response, policy, verdict);

		if (verdict.allowed()) {
			chain.doFilter(request, response);
			return;
		}

		response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds(verdict.retryAfter())));
		errorWriter.write(request, response, ErrorCode.RATE_LIMIT_EXCEEDED,
				"Too many requests. Try again in %d second(s).".formatted(seconds(verdict.retryAfter())));
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !properties.enabled();
	}

	private String keyFor(LimitedRoute route, HttpServletRequest request) {
		if (route.scope() == Scope.PRINCIPAL) {
			Long userId = actor.currentId();
			if (userId != null) {
				return "%s:user:%d".formatted(route.policy().name(), userId);
			}
		}
		return "%s:ip:%s".formatted(route.policy().name(), clientAddress(request));
	}

	private String clientAddress(HttpServletRequest request) {
		String address = request.getRemoteAddr();
		return address != null ? address : "unknown";
	}

	private void writeLimitHeaders(HttpServletResponse response, RateLimitPolicy policy,
			RateLimiter.Verdict verdict) {

		long reset = seconds(verdict.allowed() ? policy.window() : verdict.retryAfter());

		response.setHeader(LIMIT_HEADER, String.valueOf(policy.capacity()));
		response.setHeader(REMAINING_HEADER, String.valueOf(verdict.remaining()));
		response.setHeader(RESET_HEADER, String.valueOf(reset));
		response.setHeader(DRAFT_HEADER,
				"\"%s\";r=%d;t=%d".formatted(policy.name(), verdict.remaining(), reset));
	}

	private long seconds(Duration duration) {
		return Math.max(1, (duration.toMillis() + 999) / 1000);
	}

	private LimitedRoute match(HttpServletRequest request) {
		PathContainer path = PathContainer.parsePath(request.getRequestURI());
		return routes.stream()
				.filter(route -> route.matches(request.getMethod(), path))
				.findFirst()
				.orElse(null);
	}

	private enum Scope {
		CLIENT,
		PRINCIPAL
	}

	private record LimitedRoute(String method, PathPattern pattern, RateLimitPolicy policy,
			Scope scope) {

		static LimitedRoute of(String method, String pattern, RateLimitPolicy policy, Scope scope) {
			return new LimitedRoute(method, PathPatternParser.defaultInstance.parse(pattern),
					policy, scope);
		}

		boolean matches(String requestMethod, PathContainer path) {
			return this.method.equalsIgnoreCase(requestMethod) && this.pattern.matches(path);
		}
	}
}
