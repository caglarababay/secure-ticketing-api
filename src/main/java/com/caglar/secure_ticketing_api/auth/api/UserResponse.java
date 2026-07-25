package com.caglar.secure_ticketing_api.auth.api;

import java.time.Instant;
import java.util.Set;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;


public record UserResponse(Long id, String email, Set<Role> roles, Instant createdAt, Instant lastLoginAt) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getRoles(),
				user.getCreatedAt(), user.getLastLoginAt());
	}
}
