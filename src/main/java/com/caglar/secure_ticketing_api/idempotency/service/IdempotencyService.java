package com.caglar.secure_ticketing_api.idempotency.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.caglar.secure_ticketing_api.idempotency.domain.IdempotencyRecord;
import com.caglar.secure_ticketing_api.idempotency.domain.IdempotencyRecordRepository;


@Service
public class IdempotencyService {

	private static final int CLAIM_ATTEMPTS = 2;

	private final IdempotencyRecordRepository records;
	private final IdempotencyProperties properties;
	private final Clock clock;
	private final TransactionTemplate newTransaction;

	IdempotencyService(IdempotencyRecordRepository records, IdempotencyProperties properties,
			Clock clock, PlatformTransactionManager transactionManager) {

		this.records = records;
		this.properties = properties;
		this.clock = clock;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public IdempotencyClaim claim(Long userId, String key, String endpoint, String requestHash) {
		for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
			Long claimed = tryInsert(userId, key, endpoint, requestHash);
			if (claimed != null) {
				return new IdempotencyClaim.Claimed(claimed);
			}

			IdempotencyClaim verdict = inspect(userId, key, endpoint, requestHash);
			if (verdict != null) {
				return verdict;
			}
		}

		return new IdempotencyClaim.InProgress();
	}

	public void complete(Long recordId, Long resourceId, String responseHash) {
		newTransaction.executeWithoutResult(status ->
				records.findById(recordId).ifPresent(record ->
						record.markCompleted(resourceId, responseHash)));
	}

	public void discard(Long recordId) {
		newTransaction.executeWithoutResult(status -> records.deleteById(recordId));
	}

	private Long tryInsert(Long userId, String key, String endpoint, String requestHash) {
		try {
			return newTransaction.execute(status -> {
				Instant now = Instant.now(clock);
				return records.save(new IdempotencyRecord(key, userId, endpoint, requestHash,
						now, now.plus(properties.lease()), now.plus(properties.retention())))
						.getId();
			});
		}
		catch (DataIntegrityViolationException ex) {
			return null;
		}
	}

	private IdempotencyClaim inspect(Long userId, String key, String endpoint, String requestHash) {
		return newTransaction.execute(status -> {
			IdempotencyRecord record = records
					.findByUserIdAndKeyAndEndpoint(userId, key, endpoint)
					.orElse(null);

			if (record == null) {
				return null;
			}

			Instant now = Instant.now(clock);
			if (record.isExpired(now)) {
				records.delete(record);
				return null;
			}

			if (!record.matchesRequest(requestHash)) {
				return new IdempotencyClaim.Mismatch();
			}

			if (record.canReplay()) {
				return new IdempotencyClaim.Replay(record.getResourceId(), record.getResponseHash());
			}

			// Still IN_PROGRESS. 
			if (record.isLeaseExpired(now)) {
				record.renewLease(now.plus(properties.lease()));
				return new IdempotencyClaim.Claimed(record.getId());
			}

			return new IdempotencyClaim.InProgress();
		});
	}
}
