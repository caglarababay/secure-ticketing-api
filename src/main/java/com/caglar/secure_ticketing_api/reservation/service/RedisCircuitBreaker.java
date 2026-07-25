package com.caglar.secure_ticketing_api.reservation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stops a failing Redis from slowing every request down.
 */
class RedisCircuitBreaker {

	private static final int FAILURE_THRESHOLD = 3;
	private static final Duration OPEN_DURATION = Duration.ofSeconds(30);

	private final AtomicInteger consecutiveFailures = new AtomicInteger();
	private final AtomicReference<Instant> openUntil = new AtomicReference<>(Instant.EPOCH);
	private final Clock clock;

	RedisCircuitBreaker(Clock clock) {
		this.clock = clock;
	}

	boolean allowsCall() {
		return !Instant.now(clock).isBefore(openUntil.get());
	}

	void recordSuccess() {
		consecutiveFailures.set(0);
	}

	void recordFailure() {
		if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
			openUntil.set(Instant.now(clock).plus(OPEN_DURATION));
			consecutiveFailures.set(0);
		}
	}
}
