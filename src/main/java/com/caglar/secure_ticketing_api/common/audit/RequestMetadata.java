package com.caglar.secure_ticketing_api.common.audit;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;


@Component
public class RequestMetadata {

	private static final int USER_AGENT_LIMIT = 255;

	public String clientAddress() {
		HttpServletRequest request = currentRequest();
		return request != null ? request.getRemoteAddr() : null;
	}

	public String userAgent() {
		HttpServletRequest request = currentRequest();
		if (request == null) {
			return null;
		}
		String userAgent = request.getHeader("User-Agent");
		if (userAgent == null || userAgent.length() <= USER_AGENT_LIMIT) {
			return userAgent;
		}
		return userAgent.substring(0, USER_AGENT_LIMIT);
	}

	private HttpServletRequest currentRequest() {
		return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
				? attributes.getRequest()
				: null;
	}
}
