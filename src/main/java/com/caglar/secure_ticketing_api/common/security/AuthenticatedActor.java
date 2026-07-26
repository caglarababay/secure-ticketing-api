package com.caglar.secure_ticketing_api.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;


@Component
public class AuthenticatedActor {

	public Long currentId() {
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
}
