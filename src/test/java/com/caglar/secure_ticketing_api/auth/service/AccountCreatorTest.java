package com.caglar.secure_ticketing_api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.common.error.ApiException;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;


@ExtendWith(MockitoExtension.class)
class AccountCreatorTest {

	private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");

	@Mock
	private UserRepository users;

	private AccountCreator accountCreator() {
		return new AccountCreator(users, new BCryptPasswordEncoder(),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private User saveEchoes() {
		when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
		return null;
	}

	@Test
	void theRolesAreWhateverTheCallerAsksFor() {
		when(users.existsByEmail(anyString())).thenReturn(false);
		saveEchoes();

		accountCreator().create("organizer@test.com", "secret123",
				EnumSet.of(Role.ORGANIZER, Role.CUSTOMER));

		assertThat(saved().getRoles()).containsExactlyInAnyOrder(Role.ORGANIZER, Role.CUSTOMER);
	}

	@Test
	void thePasswordIsNeverStoredAsTyped() {
		when(users.existsByEmail(anyString())).thenReturn(false);
		saveEchoes();

		accountCreator().create("new@test.com", "secret123", EnumSet.of(Role.CUSTOMER));

		String hash = saved().getPasswordHash();
		assertThat(hash).isNotEqualTo("secret123").startsWith("$2");
		assertThat(new BCryptPasswordEncoder().matches("secret123", hash))
				.as("and it is a hash of the password, not of something else")
				.isTrue();
	}

	@Test
	void theAddressIsTrimmedAndLowercased() {
		when(users.existsByEmail("mixed@test.com")).thenReturn(false);
		saveEchoes();

		accountCreator().create("  MiXeD@Test.COM  ", "secret123", EnumSet.of(Role.CUSTOMER));

		assertThat(saved().getEmail()).isEqualTo("mixed@test.com");
	}

	@Test
	void theCreationTimeComesFromTheClock() {
		when(users.existsByEmail(anyString())).thenReturn(false);
		saveEchoes();

		accountCreator().create("new@test.com", "secret123", EnumSet.of(Role.CUSTOMER));

		assertThat(saved().getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void aKnownAddressIsRefusedWithoutTouchingTheDatabase() {
		when(users.existsByEmail("taken@test.com")).thenReturn(true);

		assertThatThrownBy(() -> accountCreator()
				.create("taken@test.com", "secret123", EnumSet.of(Role.CUSTOMER)))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);

		verify(users, never()).save(any());
	}

	@Test
	void theAddressIsNormalisedBeforeTheDuplicateCheck() {
		when(users.existsByEmail("taken@test.com")).thenReturn(true);

		assertThatThrownBy(() -> accountCreator()
				.create("  TAKEN@Test.com ", "secret123", EnumSet.of(Role.CUSTOMER)))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void losingTheRaceToTheUniqueIndexIsStillAConflict() {
		when(users.existsByEmail(anyString())).thenReturn(false);
		when(users.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

		assertThatThrownBy(() -> accountCreator()
				.create("race@test.com", "secret123", EnumSet.of(Role.CUSTOMER)))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
	}

	@Test
	void normaliseIsTheSameRuleTheCheckUses() {
		assertThat(accountCreator().normalise("  MiXeD@Test.COM ")).isEqualTo("mixed@test.com");
	}

	@Test
	void existsNormalisesToo() {
		when(users.existsByEmail("known@test.com")).thenReturn(true);

		assertThat(accountCreator().exists("  KNOWN@Test.COM ")).isTrue();
	}

	private User saved() {
		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		verify(users).save(captor.capture());
		return captor.getValue();
	}
}
