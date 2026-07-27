package com.caglar.secure_ticketing_api.common.error;

/**
 * Base type for failures the application raises on purpose.
 */
public class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final ErrorCode code;

	public ApiException(ErrorCode code, String message) {
		super(message);
		this.code = code;
	}

	public ApiException(ErrorCode code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public ErrorCode code() {
		return code;
	}
}
