package com.caglar.secure_ticketing_api.auth.api;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.caglar.secure_ticketing_api.common.error.ApiErrorResponseWriter;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


@Component
public class ApiErrorAccessDeniedHandler implements AccessDeniedHandler {

	private final ApiErrorResponseWriter writer;

	ApiErrorAccessDeniedHandler(ApiErrorResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {

		writer.write(request, response, ErrorCode.FORBIDDEN,
				"You do not have permission to perform this action.");
	}
}
