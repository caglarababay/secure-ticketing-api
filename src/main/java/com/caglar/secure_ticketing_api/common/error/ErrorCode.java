package com.caglar.secure_ticketing_api.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Machine-readable error identifiers, each bound to the HTTP status it maps to.
 */
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
	INVALID_TOKEN(HttpStatus.UNAUTHORIZED),
	FORBIDDEN(HttpStatus.FORBIDDEN),
	EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT),
	NOT_FOUND(HttpStatus.NOT_FOUND),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
	CONFLICT(HttpStatus.CONFLICT),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
	EVENT_ALREADY_PUBLISHED(HttpStatus.CONFLICT),
	EVENT_NOT_PUBLISHED(HttpStatus.CONFLICT),
	INSUFFICIENT_CAPACITY(HttpStatus.CONFLICT),
	INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
	CAPACITY_BELOW_RESERVED(HttpStatus.CONFLICT),
	IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST),
	IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST),
	IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY),
	IDEMPOTENCY_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT),
	RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}

	public static ErrorCode fromStatus(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> MALFORMED_REQUEST;
			case 401 -> UNAUTHORIZED;
			case 403 -> FORBIDDEN;
			case 404 -> NOT_FOUND;
			case 405 -> METHOD_NOT_ALLOWED;
			case 409 -> CONFLICT;
			case 415 -> UNSUPPORTED_MEDIA_TYPE;
			default -> status.is4xxClientError() ? MALFORMED_REQUEST : INTERNAL_ERROR;
		};
	}
}
