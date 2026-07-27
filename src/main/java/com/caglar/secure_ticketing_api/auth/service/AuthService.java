package com.caglar.secure_ticketing_api.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.caglar.secure_ticketing_api.audit.domain.AuditAction;
import com.caglar.secure_ticketing_api.audit.domain.AuditResource;
import com.caglar.secure_ticketing_api.audit.service.AuditRecorder;
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
	private final AccountCreator accounts;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final AuditRecorder audit;
	private final Clock clock;

	AuthService(UserRepository users, AccountCreator accounts, PasswordEncoder passwordEncoder,
			JwtService jwtService, AuditRecorder audit, Clock clock) {

		this.users = users;
		this.accounts = accounts;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.audit = audit;
		this.clock = clock;
	}

	/** Always CUSTOMER: nobody hands themselves a role by signing up. */
	@Transactional
	public User register(RegisterRequest request) {
		User saved = accounts.create(request.email(), request.password(), EnumSet.of(Role.CUSTOMER));

		audit.recordFor(saved.getId(), AuditAction.REGISTERED, AuditResource.USER, saved.getId());
		return saved;
	}

	@Transactional
	public TokenResponse login(LoginRequest request) {
		User user = users.findByEmail(accounts.normalise(request.email())).orElse(null);
		if (user == null) {
			throw rejectLogin(null);
		}
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw rejectLogin(user.getId());
		}

		user.recordLogin(Instant.now(clock));
		
		audit.recordFor(user.getId(), AuditAction.LOGIN_SUCCEEDED, AuditResource.USER, user.getId());
		return issueTokens(user);
	}

	private ApiException rejectLogin(Long targetedUserId) {
		audit.recordFailure(targetedUserId, AuditAction.LOGIN_FAILED, AuditResource.USER,
				targetedUserId);
		return new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password.");
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
}
