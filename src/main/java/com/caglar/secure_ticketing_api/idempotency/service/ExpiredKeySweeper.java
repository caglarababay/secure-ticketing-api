package com.caglar.secure_ticketing_api.idempotency.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.caglar.secure_ticketing_api.idempotency.domain.IdempotencyRecord;
import com.caglar.secure_ticketing_api.idempotency.domain.IdempotencyRecordRepository;


@Component
class ExpiredKeySweeper {

	private static final Logger log = LoggerFactory.getLogger(ExpiredKeySweeper.class);

	private final IdempotencyRecordRepository records;
	private final IdempotencyProperties properties;
	private final Clock clock;

	ExpiredKeySweeper(IdempotencyRecordRepository records, IdempotencyProperties properties,
			Clock clock) {

		this.records = records;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${idempotency.sweep-interval}")
	@Transactional
	public void sweep() {
		Instant now = Instant.now(clock);
		List<IdempotencyRecord> expired = records.findExpired(
				now, PageRequest.of(0, properties.sweepBatchSize()));

		if (expired.isEmpty()) {
			return;
		}

		records.deleteAll(expired);
		log.info("Removed {} expired idempotency key(s)", expired.size());
	}
}
