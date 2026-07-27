package com.caglar.secure_ticketing_api.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.common.error.ApiException;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;


@Service
public class AccountCreator {

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	AccountCreator(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	@Transactional
	public User create(String email, String rawPassword, Set<Role> roles) {
		String normalised = normalise(email);
		if (users.existsByEmail(normalised)) {
			throw duplicate(null);
		}

		User user = new User(normalised, passwordEncoder.encode(rawPassword), roles,
				Instant.now(clock));
		try {
			return users.save(user);
		}
		catch (DataIntegrityViolationException ex) {
			throw duplicate(ex);
		}
	}

	public String normalise(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	public boolean exists(String email) {
		return users.existsByEmail(normalise(email));
	}

	private ApiException duplicate(Throwable cause) {
		return cause == null
				? new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS,
						"That email address is already registered.")
				: new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS,
						"That email address is already registered.", cause);
	}
}
