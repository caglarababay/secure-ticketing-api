package com.caglar.secure_ticketing_api.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.caglar.secure_ticketing_api.auth.api.LoginRequest;
import com.caglar.secure_ticketing_api.auth.api.RegisterRequest;
import com.caglar.secure_ticketing_api.auth.api.TokenResponse;
import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.common.error.ApiException;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;

@Service
public class AuthService {

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final Clock clock;

	AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.clock = clock;
	}

	@Transactional
	public User register(RegisterRequest request) {
		String email = normalise(request.email());
		if (users.existsByEmail(email)) {
			throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS, "That email address is already registered.");
		}

		User user = new User(email, passwordEncoder.encode(request.password()),
				EnumSet.of(Role.CUSTOMER), Instant.now(clock));
		try {
			return users.save(user);
		}
		catch (DataIntegrityViolationException ex) {
			throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS,
					"That email address is already registered.", ex);
		}
	}

	@Transactional
	public TokenResponse login(LoginRequest request) {
		User user = users.findByEmail(normalise(request.email()))
				.filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
				.orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password."));

		user.recordLogin(Instant.now(clock));
		return issueTokens(user);
	}

	@Transactional(readOnly = true)
	public TokenResponse refresh(String refreshToken) {
		Jwt jwt = jwtService.decode(refreshToken, false);
		User user = users.findById(Long.valueOf(jwt.getSubject()))
				.orElseThrow(() -> new ApiException(ErrorCode.INVALID_TOKEN, "Token is invalid or expired."));

		return issueTokens(user);
	}

	@Transactional(readOnly = true)
	public User requireById(Long id) {
		return users.findById(id)
				.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Account no longer exists."));
	}

	private TokenResponse issueTokens(User user) {
		return TokenResponse.bearer(jwtService.createAccessToken(user),
				jwtService.createRefreshToken(user), jwtService.accessTokenTtlSeconds());
	}

	private String normalise(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}
}
