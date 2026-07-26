package com.caglar.secure_ticketing_api.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;


class RateLimitConcurrencyTest {

	private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");
	private static final int THREADS = 40;

	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void aBurstOnOneKeyNeverExceedsTheBudget() throws Exception {
		int capacity = 10;
		RateLimitPolicy policy = new RateLimitPolicy("burst", capacity, Duration.ofMinutes(1));
		LocalRateLimiter limiter = new LocalRateLimiter(clock, 1000);

		AtomicInteger allowed = countAllowed(limiter, policy, index -> "one-key");

		assertThat(allowed.get())
				.as("exactly the budget got through, no more and no fewer")
				.isEqualTo(capacity);
	}

	@Test
	void aBurstUnderTheBudgetIsAllowedInFull() throws Exception {
		RateLimitPolicy policy = new RateLimitPolicy("roomy", THREADS * 2, Duration.ofMinutes(1));
		LocalRateLimiter limiter = new LocalRateLimiter(clock, 1000);

		AtomicInteger allowed = countAllowed(limiter, policy, index -> "one-key");

		assertThat(allowed.get()).isEqualTo(THREADS);
	}

	@Test
	void separateKeysAreCountedSeparatelyUnderLoad() throws Exception {
		RateLimitPolicy policy = new RateLimitPolicy("split", 1, Duration.ofMinutes(1));
		LocalRateLimiter limiter = new LocalRateLimiter(clock, 1000);

		AtomicInteger allowed = countAllowed(limiter, policy, index -> "key-" + index);

		assertThat(allowed.get())
				.as("each key had a budget of one, and each got its one")
				.isEqualTo(THREADS);
	}

	private AtomicInteger countAllowed(LocalRateLimiter limiter, RateLimitPolicy policy,
			java.util.function.IntFunction<String> keyFor) throws InterruptedException {

		AtomicInteger allowed = new AtomicInteger();
		CountDownLatch startLine = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(THREADS);

		try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
			for (int i = 0; i < THREADS; i++) {
				int index = i;
				pool.submit(() -> {
					try {
						startLine.await();
						if (limiter.tryConsume(keyFor.apply(index), policy).allowed()) {
							allowed.incrementAndGet();
						}
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
					finally {
						finished.countDown();
					}
				});
			}

			startLine.countDown();
			assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
		}

		return allowed;
	}
}
