package com.caglar.secure_ticketing_api.common.error;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The standard error body returned by every failing request.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
		Instant timestamp,
		int status,
		String code,
		String message,
		String path,
		List<FieldViolation> errors) {

	public record FieldViolation(String field, String message) {
	}
}
