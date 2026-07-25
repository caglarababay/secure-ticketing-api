package com.caglar.secure_ticketing_api.idempotency.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.caglar.secure_ticketing_api.idempotency.domain.IdempotencyRecord;
import com.caglar.secure_ticketing_api.idempotency.domain.IdempotencyRecordRepository;
import com.caglar.secure_ticketing_api.idempotency.domain.IdempotencyStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;


@SpringBootTest
@ActiveProfiles("test")
class IdempotencyServiceTest {

	private static final Long USER = 1L;
	private static final String ENDPOINT = "POST /api/events/{eventId}/reservations";
	private static final String HASH = "a".repeat(64);

	@Autowired
	private IdempotencyService idempotency;

	@Autowired
	private IdempotencyRecordRepository records;

	@PersistenceContext
	private EntityManager entityManager;

	private TransactionTemplate transactionTemplate;

	@Autowired
	void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void setUp() {
		records.deleteAll();
	}

	// --- claiming --------------------------------------------------------------

	@Test
	void anUnseenKeyIsClaimed() {
		IdempotencyClaim claim = idempotency.claim(USER, "fresh", ENDPOINT, HASH);

		assertThat(claim).isInstanceOf(IdempotencyClaim.Claimed.class);
		assertThat(records.count()).isEqualTo(1);
		assertThat(records.findAll().getFirst().getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
	}

	@Test
	void aSecondClaimWhileTheFirstIsRunningIsToldToRetry() {
		idempotency.claim(USER, "busy", ENDPOINT, HASH);

		assertThat(idempotency.claim(USER, "busy", ENDPOINT, HASH))
				.isInstanceOf(IdempotencyClaim.InProgress.class);
	}

	@Test
	void aDifferentPayloadUnderTheSameKeyIsAMismatch() {
		idempotency.claim(USER, "reused", ENDPOINT, HASH);

		assertThat(idempotency.claim(USER, "reused", ENDPOINT, "b".repeat(64)))
				.isInstanceOf(IdempotencyClaim.Mismatch.class);
	}

	@Test
	void aFinishedKeyReplaysItsResource() {
		Long recordId = claimedId("done");
		idempotency.complete(recordId, 42L, "response-hash");

		IdempotencyClaim claim = idempotency.claim(USER, "done", ENDPOINT, HASH);

		assertThat(claim).isEqualTo(new IdempotencyClaim.Replay(42L, "response-hash"));
	}

	@Test
	void keysAreScopedToTheirUser() {
		idempotency.claim(USER, "shared", ENDPOINT, HASH);

		assertThat(idempotency.claim(2L, "shared", ENDPOINT, HASH))
				.isInstanceOf(IdempotencyClaim.Claimed.class);
		assertThat(records.count()).isEqualTo(2);
	}

	@Test
	void theSameKeyOnAnotherEndpointIsIndependent() {
		idempotency.claim(USER, "shared", ENDPOINT, HASH);

		assertThat(idempotency.claim(USER, "shared", "POST /api/other", HASH))
				.isInstanceOf(IdempotencyClaim.Claimed.class);
	}

	// --- discard ----------------------------------------------------------------

	@Test
	void discardingLeavesNoTraceAndFreesTheKey() {
		Long recordId = claimedId("failed");

		idempotency.discard(recordId);

		assertThat(records.count()).isZero();
		assertThat(idempotency.claim(USER, "failed", ENDPOINT, HASH))
				.isInstanceOf(IdempotencyClaim.Claimed.class);
	}

	@Test
	void aDiscardedKeyDoesNotHoldTheOldFingerprintAgainstTheClient() {
		idempotency.discard(claimedId("failed"));

		assertThat(idempotency.claim(USER, "failed", ENDPOINT, "c".repeat(64)))
				.isInstanceOf(IdempotencyClaim.Claimed.class);
	}

	// --- crash recovery -----------------------------------------------------------

	@Test
	void aLapsedLeaseIsHandedToTheNextCaller() {
		claimedId("abandoned");
		assertThat(idempotency.claim(USER, "abandoned", ENDPOINT, HASH))
				.as("while the lease holds, retries wait")
				.isInstanceOf(IdempotencyClaim.InProgress.class);

		lapseLease("abandoned");

		assertThat(idempotency.claim(USER, "abandoned", ENDPOINT, HASH))
				.as("once it lapses, the work may be redone")
				.isInstanceOf(IdempotencyClaim.Claimed.class);
		assertThat(records.count()).as("taken over, not duplicated").isEqualTo(1);
	}

	@Test
	void takingOverALapsedLeaseRenewsIt() {
		claimedId("abandoned");
		lapseLease("abandoned");
		idempotency.claim(USER, "abandoned", ENDPOINT, HASH);

		assertThat(idempotency.claim(USER, "abandoned", ENDPOINT, HASH))
				.isInstanceOf(IdempotencyClaim.InProgress.class);
	}

	@Test
	void aLapsedLeaseOnAFinishedRecordStillReplays() {
		idempotency.complete(claimedId("done"), 7L, "hash");
		lapseLease("done");

		assertThat(idempotency.claim(USER, "done", ENDPOINT, HASH))
				.isInstanceOf(IdempotencyClaim.Replay.class);
	}

	// --- retention -----------------------------------------------------------------

	@Test
	void anExpiredRecordIsReplacedRatherThanReplayed() {
		idempotency.complete(claimedId("stale"), 9L, "hash");
		expire("stale");

		assertThat(idempotency.claim(USER, "stale", ENDPOINT, HASH))
				.isInstanceOf(IdempotencyClaim.Claimed.class);
		assertThat(records.count()).isEqualTo(1);
		assertThat(records.findAll().getFirst().getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
	}

	// --- helpers --------------------------------------------------------------------

	private Long claimedId(String key) {
		IdempotencyClaim claim = idempotency.claim(USER, key, ENDPOINT, HASH);
		return ((IdempotencyClaim.Claimed) claim).recordId();
	}

	private void lapseLease(String key) {
		update(key, "r.lockedUntil = :past");
	}

	private void expire(String key) {
		update(key, "r.expiresAt = :past");
	}

	private void update(String key, String assignment) {
		transactionTemplate.executeWithoutResult(status -> entityManager
				.createQuery("update IdempotencyRecord r set " + assignment + " where r.key = :key")
				.setParameter("past", Instant.now().minus(Duration.ofMinutes(1)))
				.setParameter("key", key)
				.executeUpdate());
		records.findAll().forEach(IdempotencyRecord::getId);
	}
}
