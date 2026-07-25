package com.caglar.secure_ticketing_api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.common.error.ApiException;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;

class JwtServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");
	private static final String SECRET = "asdasd-asdasd-asdasd-asdasd-asdasd-asdasd";

	private final JwtProperties properties =
			new JwtProperties(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));

	private final JwtService jwtService = jwtServiceAt(NOW);

	private JwtService jwtServiceAt(Instant instant) {
		return new JwtService(properties, Clock.fixed(instant, ZoneOffset.UTC));
	}

	private User user() {
		User user = new User("user@test.com", "hash", EnumSet.of(Role.ORGANIZER), NOW);
		ReflectionTestUtils.setField(user, "id", 42L);
		return user;
	}

	@Test
	void accessTokenCarriesSubjectEmailAndRoles() {
		Jwt jwt = jwtService.decode(jwtService.createAccessToken(user()), true);

		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(jwt.getClaimAsString("email")).isEqualTo("user@test.com");
		assertThat(jwtService.rolesOf(jwt)).containsExactly("ORGANIZER");
		assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
	}

	@Test
	void refreshTokenLivesLongerThanAccessToken() {
		Jwt refresh = jwtService.decode(jwtService.createRefreshToken(user()), false);

		assertThat(refresh.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
	}

	@Test
	void refreshTokenIsRejectedWhereAnAccessTokenIsRequired() {
		String refreshToken = jwtService.createRefreshToken(user());

		assertThatThrownBy(() -> jwtService.decode(refreshToken, true))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.INVALID_TOKEN);
	}

	@Test
	void accessTokenIsRejectedWhereARefreshTokenIsRequired() {
		String accessToken = jwtService.createAccessToken(user());

		assertThatThrownBy(() -> jwtService.decode(accessToken, false))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void expiredTokenIsRejected() {
		String token = jwtService.createAccessToken(user());
		JwtService laterService = jwtServiceAt(NOW.plus(Duration.ofMinutes(30)));

		assertThatThrownBy(() -> laterService.decode(token, true))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.INVALID_TOKEN);
	}

	@Test
	void tokenSignedWithAnotherSecretIsRejected() {
		JwtProperties otherSecret = new JwtProperties(
				"xxxxxx-xxxxxx-xxxxxx-xxxxxx-xxxxxx-xxxxxx", Duration.ofMinutes(15), Duration.ofDays(7));
		String foreignToken = new JwtService(otherSecret, Clock.fixed(NOW, ZoneOffset.UTC))
				.createAccessToken(user());

		assertThatThrownBy(() -> jwtService.decode(foreignToken, true))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void garbageIsRejected() {
		assertThatThrownBy(() -> jwtService.decode("not.a.token", true))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void rolesOfReturnsEmptyListWhenClaimIsAbsent() {
		User roleless = new User("none@test.com", "hash", Set.of(), NOW);
		ReflectionTestUtils.setField(roleless, "id", 7L);

		Jwt jwt = jwtService.decode(jwtService.createAccessToken(roleless), true);

		assertThat(jwtService.rolesOf(jwt)).isEmpty();
	}
}
