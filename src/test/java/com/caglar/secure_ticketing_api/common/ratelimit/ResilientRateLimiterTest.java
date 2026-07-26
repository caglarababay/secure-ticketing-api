package com.caglar.secure_ticketing_api.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.caglar.secure_ticketing_api.common.ratelimit.RateLimiter.Verdict;
import com.caglar.secure_ticketing_api.common.resilience.RedisCircuitBreaker;


class ResilientRateLimiterTest {

	private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");
	private static final int FAILURES_TO_OPEN = 3;
	private static final Duration OPEN_FOR = Duration.ofSeconds(30);
	private static final RateLimitPolicy POLICY = new RateLimitPolicy("test", 100, Duration.ofMinutes(1));

	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void aWorkingSharedCounterIsUsedAsIs() {
		RecordingLimiter local = new RecordingLimiter();
		RateLimiter limiter = resilient((key, policy) -> Verdict.allowed(42), local);

		Verdict verdict = limiter.tryConsume("a", POLICY);

		assertThat(verdict.remaining()).isEqualTo(42);
		assertThat(local.calls).isEmpty();
	}

	// --- the shared counter fails ------------------------------------------------

	@Test
	void anUnreachableSharedCounterDoesNotRefuseTheRequest() {
		RateLimiter limiter = resilient(failing(), new RecordingLimiter());

		assertThat(limiter.tryConsume("a", POLICY).allowed())
				.as("a Redis outage must not become our outage")
				.isTrue();
	}

	@Test
	void theRequestFallsThroughToThisInstance() {
		RecordingLimiter local = new RecordingLimiter();
		resilient(failing(), local).tryConsume("a", POLICY);

		assertThat(local.calls).hasSize(1);
	}

	@Test
	void theFallbackBudgetIsSplitBetweenInstances() {
		RecordingLimiter local = new RecordingLimiter();
		new ResilientRateLimiter(failing(), local, new RedisCircuitBreaker(FAILURES_TO_OPEN, OPEN_FOR, clock), 4)
				.tryConsume("a", POLICY);

		assertThat(local.calls.getFirst().capacity()).isEqualTo(25);
	}

	@Test
	void aSingleInstanceKeepsTheWholePolicy() {
		RecordingLimiter local = new RecordingLimiter();
		resilient(failing(), local).tryConsume("a", POLICY);

		assertThat(local.calls.getFirst().capacity()).isEqualTo(100);
	}

	// --- the breaker ----------------------------------------------------------------

	@Test
	void repeatedFailuresStopTheSharedCounterBeingCalledAtAll() {
		CountingLimiter shared = new CountingLimiter();
		RateLimiter limiter = resilient(shared, new RecordingLimiter());

		for (int i = 0; i < 10; i++) {
			limiter.tryConsume("a", POLICY);
		}

		assertThat(shared.attempts)
				.as("the configured number of failures opens the breaker; the rest skip Redis")
				.isEqualTo(FAILURES_TO_OPEN);
	}

	@Test
	void everyRequestIsStillAnsweredWhileTheBreakerIsOpen() {
		RateLimiter limiter = resilient(new CountingLimiter(), new RecordingLimiter());

		for (int i = 0; i < 10; i++) {
			assertThat(limiter.tryConsume("a", POLICY).allowed()).isTrue();
		}
	}

	/** A local limiter that actually counts still refuses once its share is gone. */
	@Test
	void theFallbackStillEnforcesALimit() {
		RateLimitPolicy small = new RateLimitPolicy("test", 2, Duration.ofMinutes(1));
		RateLimiter limiter = new ResilientRateLimiter(failing(),
				new LocalRateLimiter(clock, 100), new RedisCircuitBreaker(FAILURES_TO_OPEN, OPEN_FOR, clock), 1);

		assertThat(limiter.tryConsume("a", small).allowed()).isTrue();
		assertThat(limiter.tryConsume("a", small).allowed()).isTrue();
		assertThat(limiter.tryConsume("a", small).allowed())
				.as("falling back is not the same as giving up")
				.isFalse();
	}

	private RateLimiter resilient(RateLimiter shared, RateLimiter local) {
		return new ResilientRateLimiter(shared, local, new RedisCircuitBreaker(FAILURES_TO_OPEN, OPEN_FOR, clock), 1);
	}

	private RateLimiter failing() {
		return (key, policy) -> {
			throw new IllegalStateException("Redis is down");
		};
	}

	private static final class RecordingLimiter implements RateLimiter {

		private final List<RateLimitPolicy> calls = new ArrayList<>();

		@Override
		public Verdict tryConsume(String key, RateLimitPolicy policy) {
			calls.add(policy);
			return Verdict.allowed(1);
		}
	}

	private static final class CountingLimiter implements RateLimiter {

		private int attempts;

		@Override
		public Verdict tryConsume(String key, RateLimitPolicy policy) {
			attempts++;
			throw new IllegalStateException("Redis is down");
		}
	}
}
