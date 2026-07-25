package com.caglar.secure_ticketing_api.common.error;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception that escapes a controller into an {@link ApiError}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private final Clock clock;

	GlobalExceptionHandler(Clock clock) {
		this.clock = clock;
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiError> handleApiException(ApiException ex, WebRequest request) {
		ErrorCode code = ex.code();
		return ResponseEntity.status(code.status())
				.body(apiError(code.status(), code, ex.getMessage(), request, null));
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(apiError(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
						"You do not have permission to perform this action.", request, null));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Unhandled exception for {}", path(request), ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(apiError(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
						"An unexpected error occurred.", request, null));
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(
			MethodArgumentNotValidException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {

		List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> new ApiError.FieldViolation(
						fieldError.getField(), fieldError.getDefaultMessage()))
				.toList();

		ApiError error = apiError(status, ErrorCode.VALIDATION_FAILED,
				"Request validation failed.", request, violations);

		return ResponseEntity.status(status).headers(headers).body(error);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(
			Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {

		ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, status, request);
		if (response == null) {
			return null;
		}
		// handleMethodArgumentNotValid above already produced the final body.
		if (response.getBody() instanceof ApiError) {
			return response;
		}

		HttpStatusCode responseStatus = response.getStatusCode();
		ApiError error = apiError(responseStatus, ErrorCode.fromStatus(responseStatus),
				detailOf(response.getBody(), responseStatus), request, null);

		return ResponseEntity.status(responseStatus)
				.headers(response.getHeaders())
				.body(error);
	}

	private ApiError apiError(HttpStatusCode status, ErrorCode code, String message,
			WebRequest request, List<ApiError.FieldViolation> violations) {

		return new ApiError(Instant.now(clock), status.value(), code.name(), message,
				path(request), violations);
	}

	private String detailOf(Object body, HttpStatusCode status) {
		if (body instanceof ProblemDetail problemDetail && problemDetail.getDetail() != null) {
			return problemDetail.getDetail();
		}
		return HttpStatus.valueOf(status.value()).getReasonPhrase();
	}

	private String path(WebRequest request) {
		return request instanceof ServletWebRequest servletRequest
				? servletRequest.getRequest().getRequestURI()
				: null;
	}
}
