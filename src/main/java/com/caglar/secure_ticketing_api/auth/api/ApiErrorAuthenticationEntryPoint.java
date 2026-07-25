package com.caglar.secure_ticketing_api.auth.api;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.caglar.secure_ticketing_api.common.error.ApiErrorResponseWriter;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


@Component
public class ApiErrorAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ApiErrorResponseWriter writer;

	ApiErrorAuthenticationEntryPoint(ApiErrorResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {

		writer.write(request, response, ErrorCode.UNAUTHORIZED,
				"Authentication is required to access this resource.");
	}
}
