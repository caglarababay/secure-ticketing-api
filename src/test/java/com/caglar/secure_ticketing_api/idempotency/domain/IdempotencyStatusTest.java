package com.caglar.secure_ticketing_api.idempotency.domain;

import static com.caglar.secure_ticketing_api.idempotency.domain.IdempotencyStatus.COMPLETED;
import static com.caglar.secure_ticketing_api.idempotency.domain.IdempotencyStatus.IN_PROGRESS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class IdempotencyStatusTest {

	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

	@Test
	void onlyAFinishedRequestCanBeReplayed() {
		assertThat(IN_PROGRESS.canReplay()).isFalse();
		assertThat(COMPLETED.canReplay()).isTrue();
	}

	@Test
	void onlyCompletedIsTerminal() {
		assertThat(IN_PROGRESS.isTerminal()).isFalse();
		assertThat(COMPLETED.isTerminal()).isTrue();
	}

	@ParameterizedTest
	@EnumSource(IdempotencyStatus.class)
	void everyStateAnswersBothQuestions(IdempotencyStatus status) {
		assertThat(status.canReplay()).isIn(true, false);
		assertThat(status.isTerminal()).isIn(true, false);
	}

	@Test
	void aFreshRecordIsNeitherExpiredNorLapsed() {
		IdempotencyRecord record = record(NOW.plus(Duration.ofSeconds(30)), NOW.plus(Duration.ofHours(24)));

		assertThat(record.isExpired(NOW)).isFalse();
		assertThat(record.isLeaseExpired(NOW)).isFalse();
	}

	@Test
	void aLapsedLeaseDoesNotMeanTheRecordExpired() {
		IdempotencyRecord record = record(NOW.plus(Duration.ofSeconds(30)), NOW.plus(Duration.ofHours(24)));
		Instant later = NOW.plus(Duration.ofMinutes(1));

		assertThat(record.isLeaseExpired(later)).isTrue();
		assertThat(record.isExpired(later)).isFalse();
	}

	@Test
	void pastRetentionTheRecordIsExpired() {
		IdempotencyRecord record = record(NOW.plus(Duration.ofSeconds(30)), NOW.plus(Duration.ofHours(24)));

		assertThat(record.isExpired(NOW.plus(Duration.ofHours(25)))).isTrue();
	}

	@Test
	void renewingTheLeaseHandsTheRecordToANewCaller() {
		IdempotencyRecord record = record(NOW, NOW.plus(Duration.ofHours(24)));
		Instant later = NOW.plus(Duration.ofMinutes(1));
		assertThat(record.isLeaseExpired(later)).isTrue();

		record.renewLease(later.plus(Duration.ofSeconds(30)));

		assertThat(record.isLeaseExpired(later)).isFalse();
	}

	@Test
	void completingRecordsTheResourceAndFlipsTheStatus() {
		IdempotencyRecord record = record(NOW, NOW.plus(Duration.ofHours(24)));
		assertThat(record.canReplay()).isFalse();

		record.markCompleted(42L, "abc");

		assertThat(record.getStatus()).isEqualTo(COMPLETED);
		assertThat(record.getResourceId()).isEqualTo(42L);
		assertThat(record.getResponseHash()).isEqualTo("abc");
		assertThat(record.canReplay()).isTrue();
	}

	@Test
	void theRequestHashIsComparedExactly() {
		IdempotencyRecord record = record(NOW, NOW.plus(Duration.ofHours(24)));

		assertThat(record.matchesRequest("hash")).isTrue();
		assertThat(record.matchesRequest("HASH")).isFalse();
		assertThat(record.matchesRequest("other")).isFalse();
	}

	private IdempotencyRecord record(Instant lockedUntil, Instant expiresAt) {
		return new IdempotencyRecord("key", 1L, "POST /x", "hash", NOW, lockedUntil, expiresAt);
	}
}
