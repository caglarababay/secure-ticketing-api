package com.caglar.secure_ticketing_api.event.api;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.auth.service.JwtService;


final class EventTestSupport {

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;

	EventTestSupport(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
	}

	User createUser(String email, Role... roles) {
		Set<Role> roleSet = roles.length == 0 ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(Set.of(roles));
		return users.save(new User(email, passwordEncoder.encode("secret123"), roleSet, Instant.now()));
	}

	String bearerFor(User user) {
		return "Bearer " + jwtService.createAccessToken(user);
	}

	static String eventJson(String title, Instant startsAt, Instant endsAt, int capacity) {
		return """
				{"title":"%s","venue":"Arena","startsAt":"%s","endsAt":"%s","capacity":%d}
				""".formatted(title, startsAt, endsAt, capacity);
	}
}
