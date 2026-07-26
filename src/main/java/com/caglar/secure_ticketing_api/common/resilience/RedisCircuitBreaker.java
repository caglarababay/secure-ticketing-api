package com.caglar.secure_ticketing_api.common.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stops a failing Redis from slowing every request down.
 */
public class RedisCircuitBreaker {

	private final AtomicInteger consecutiveFailures = new AtomicInteger();
	private final AtomicReference<Instant> openUntil = new AtomicReference<>(Instant.EPOCH);

	private final int failureThreshold;
	private final Duration openDuration;
	private final Clock clock;

	public RedisCircuitBreaker(CircuitBreakerProperties properties, Clock clock) {
		this(properties.failureThreshold(), properties.openDuration(), clock);
	}

	public RedisCircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
		this.failureThreshold = failureThreshold;
		this.openDuration = openDuration;
		this.clock = clock;
	}

	public boolean allowsCall() {
		return !Instant.now(clock).isBefore(openUntil.get());
	}

	public void recordSuccess() {
		consecutiveFailures.set(0);
	}

	public void recordFailure() {
		if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
			openUntil.set(Instant.now(clock).plus(openDuration));
			consecutiveFailures.set(0);
		}
	}
}
