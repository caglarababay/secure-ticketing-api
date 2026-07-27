package com.caglar.secure_ticketing_api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

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

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

	@Mock
	private UserRepository users;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private JwtService jwtService;

	@Mock
	private AccountCreator accounts;

	@Mock
	private AuditRecorder audit;

	private AuthService authService() {
		lenient().when(accounts.normalise(anyString()))
				.thenAnswer(call -> call.getArgument(0, String.class).trim().toLowerCase(Locale.ROOT));
		return new AuthService(users, accounts, passwordEncoder, jwtService, audit,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private User existingUser(String email, String hash) {
		User user = new User(email, hash, EnumSet.of(Role.CUSTOMER), NOW.minus(Duration.ofDays(1)));
		ReflectionTestUtils.setField(user, "id", 1L);
		return user;
	}

	// --- register -----------------------------------------------------------

	@Test
	void registerAlwaysAsksForCustomerAndNothingMore() {
		User created = existingUser("new@test.com", "hashed");
		when(accounts.create(eq("new@test.com"), eq("secret123"), anySet())).thenReturn(created);

		authService().register(new RegisterRequest("new@test.com", "secret123"));

		ArgumentCaptor<java.util.Set<Role>> roles = ArgumentCaptor.forClass(java.util.Set.class);
		verify(accounts).create(anyString(), anyString(), roles.capture());
		assertThat(roles.getValue())
				.as("signing up must never be a way to grant yourself a role")
				.containsExactly(Role.CUSTOMER);
	}

	@Test
	void registrationIsRecordedAgainstTheNewAccount() {
		User created = existingUser("new@test.com", "hashed");
		when(accounts.create(anyString(), anyString(), anySet())).thenReturn(created);

		authService().register(new RegisterRequest("new@test.com", "secret123"));

		verify(audit).recordFor(created.getId(), AuditAction.REGISTERED, AuditResource.USER,
				created.getId());
	}

	@Test
	void registerPropagatesADuplicateAddress() {
		when(accounts.create(anyString(), anyString(), anySet()))
				.thenThrow(new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS, "taken"));

		assertThatThrownBy(() -> authService().register(new RegisterRequest("taken@test.com", "secret123")))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);

		verify(audit, never()).recordFor(any(), any(), any(), any());
	}

	// --- login --------------------------------------------------------------

	@Test
	void loginReturnsTokensAndRecordsLoginTime() {
		User user = existingUser("user@test.com", "hash");
		when(users.findByEmail("user@test.com")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("secret123", "hash")).thenReturn(true);
		when(jwtService.createAccessToken(user)).thenReturn("access-token");
		when(jwtService.createRefreshToken(user)).thenReturn("refresh-token");
		when(jwtService.accessTokenTtlSeconds()).thenReturn(900L);

		TokenResponse response = authService().login(new LoginRequest("user@test.com", "secret123"));

		assertThat(response.accessToken()).isEqualTo("access-token");
		assertThat(response.refreshToken()).isEqualTo("refresh-token");
		assertThat(response.tokenType()).isEqualTo("Bearer");
		assertThat(response.expiresIn()).isEqualTo(900L);
		assertThat(user.getLastLoginAt()).isEqualTo(NOW);
	}

	@Test
	void loginRejectsWrongPassword() {
		User user = existingUser("user@test.com", "hash");
		when(users.findByEmail("user@test.com")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

		assertThatThrownBy(() -> authService().login(new LoginRequest("user@test.com", "wrong")))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.INVALID_CREDENTIALS);

		assertThat(user.getLastLoginAt()).isNull();
	}

	@Test
	void loginReportsUnknownEmailIdenticallyToWrongPassword() {
		when(users.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> authService().login(new LoginRequest("nobody@test.com", "secret123")))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.INVALID_CREDENTIALS);
	}

	// --- refresh ------------------------------------------------------------

	@Test
	void refreshRejectsTokenForDeletedUser() {
		var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token")
				.header("alg", "HS256")
				.subject("99")
				.claim("type", "refresh")
				.build();
		when(jwtService.decode("token", false)).thenReturn(jwt);
		when(users.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> authService().refresh("token"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.INVALID_TOKEN);
	}
}
