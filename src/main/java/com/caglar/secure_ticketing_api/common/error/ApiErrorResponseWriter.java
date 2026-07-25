package com.caglar.secure_ticketing_api.common.error;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;


@Component
public class ApiErrorResponseWriter {

	private final ObjectMapper objectMapper;
	private final Clock clock;

	ApiErrorResponseWriter(ObjectMapper objectMapper, Clock clock) {
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public void write(HttpServletRequest request, HttpServletResponse response,
			ErrorCode code, String message) throws IOException {

		ApiError body = new ApiError(Instant.now(clock), code.status().value(), code.name(),
				message, request.getRequestURI(), null);

		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write(objectMapper.writeValueAsString(body));
	}
}
