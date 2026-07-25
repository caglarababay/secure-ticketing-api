package com.caglar.secure_ticketing_api.idempotency.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.caglar.secure_ticketing_api.idempotency.domain.IdempotencyRecord;
import com.caglar.secure_ticketing_api.idempotency.domain.IdempotencyRecordRepository;

@SpringBootTest
@ActiveProfiles("test")
class ExpiredKeySweeperTest {

	private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
	private static final String ENDPOINT = "POST /api/events/{eventId}/reservations";

	@Autowired
	private IdempotencyRecordRepository records;

	@Autowired
	private IdempotencyProperties properties;

	private ExpiredKeySweeper sweeper;

	@BeforeEach
	void setUp() {
		records.deleteAll();
		sweeper = new ExpiredKeySweeper(records, properties, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void recordsPastTheirRetentionWindowAreRemoved() {
		save("expired", NOW.minus(Duration.ofMinutes(1)));

		sweeper.sweep();

		assertThat(records.count()).isZero();
	}

	@Test
	void liveRecordsAreLeftAlone() {
		save("live", NOW.plus(Duration.ofHours(1)));

		sweeper.sweep();

		assertThat(records.count()).isEqualTo(1);
	}

	@Test
	void onlyTheExpiredHalfIsRemoved() {
		save("old-1", NOW.minus(Duration.ofHours(2)));
		save("old-2", NOW.minus(Duration.ofMinutes(5)));
		save("fresh-1", NOW.plus(Duration.ofHours(2)));
		save("fresh-2", NOW.plus(Duration.ofHours(23)));

		sweeper.sweep();

		assertThat(records.findAll())
				.extracting(IdempotencyRecord::getKey)
				.containsExactlyInAnyOrder("fresh-1", "fresh-2");
	}

	/** A record expiring exactly now has not passed its window yet. */
	@Test
	void aRecordExpiringOnTheDotSurvives() {
		save("boundary", NOW);

		sweeper.sweep();

		assertThat(records.count()).isEqualTo(1);
	}

	@Test
	void anEmptyTableIsHandledWithoutComplaint() {
		sweeper.sweep();

		assertThat(records.count()).isZero();
	}

	@Test
	void aSingleRunIsBoundedByTheBatchSize() {
		int batch = properties.sweepBatchSize();
		for (int i = 0; i <= batch; i++) {
			save("expired-" + i, NOW.minus(Duration.ofMinutes(1)));
		}

		sweeper.sweep();

		assertThat(records.count()).as("the overflow waits for the next run").isEqualTo(1);
	}

	private void save(String key, Instant expiresAt) {
		records.save(new IdempotencyRecord(key, 1L, ENDPOINT, "hash",
				NOW.minus(Duration.ofHours(24)), NOW, expiresAt));
	}
}
