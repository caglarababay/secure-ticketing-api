package com.caglar.secure_ticketing_api.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;


class RedisCircuitBreakerTest {

	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");
	private static final int FAILURES_TO_OPEN = 3;
	private static final Duration OPEN_FOR = Duration.ofSeconds(30);

	private RedisCircuitBreaker breakerAt(Instant instant) {
		return new RedisCircuitBreaker(FAILURES_TO_OPEN, OPEN_FOR,
				Clock.fixed(instant, ZoneOffset.UTC));
	}

	@Test
	void startsClosedSoCallsAreAllowed() {
		assertThat(breakerAt(NOW).allowsCall()).isTrue();
	}

	@Test
	void staysClosedBelowTheFailureThreshold() {
		RedisCircuitBreaker breaker = breakerAt(NOW);

		breaker.recordFailure();
		breaker.recordFailure();

		assertThat(breaker.allowsCall()).isTrue();
	}

	@Test
	void opensOnceTheThresholdIsReached() {
		RedisCircuitBreaker breaker = breakerAt(NOW);

		breaker.recordFailure();
		breaker.recordFailure();
		breaker.recordFailure();

		assertThat(breaker.allowsCall()).isFalse();
	}

	@Test
	void successResetsTheFailureCount() {
		RedisCircuitBreaker breaker = breakerAt(NOW);

		breaker.recordFailure();
		breaker.recordFailure();
		breaker.recordSuccess();
		breaker.recordFailure();
		breaker.recordFailure();

		assertThat(breaker.allowsCall()).isTrue();
	}

	@Test
	void reopensOnlyAfterTheOpenWindowHasPassed() {
		MutableClock clock = new MutableClock(NOW);
		RedisCircuitBreaker breaker = new RedisCircuitBreaker(FAILURES_TO_OPEN, OPEN_FOR, clock);

		breaker.recordFailure();
		breaker.recordFailure();
		breaker.recordFailure();
		assertThat(breaker.allowsCall()).isFalse();

		clock.advance(Duration.ofSeconds(29));
		assertThat(breaker.allowsCall()).isFalse();

		clock.advance(Duration.ofSeconds(2));
		assertThat(breaker.allowsCall()).isTrue();
	}

	@Test
	void closingResetsTheThreshold() {
		MutableClock clock = new MutableClock(NOW);
		RedisCircuitBreaker breaker = new RedisCircuitBreaker(FAILURES_TO_OPEN, OPEN_FOR, clock);

		breaker.recordFailure();
		breaker.recordFailure();
		breaker.recordFailure();
		clock.advance(Duration.ofSeconds(31));

		breaker.recordFailure();
		assertThat(breaker.allowsCall()).isTrue();

		breaker.recordFailure();
		breaker.recordFailure();
		assertThat(breaker.allowsCall()).isFalse();
	}

	@Test
	void theFailureThresholdComesFromConfiguration() {
		RedisCircuitBreaker breaker = new RedisCircuitBreaker(5, OPEN_FOR,
				Clock.fixed(NOW, ZoneOffset.UTC));

		for (int i = 0; i < 4; i++) {
			breaker.recordFailure();
		}
		assertThat(breaker.allowsCall()).as("four failures is under a threshold of five").isTrue();

		breaker.recordFailure();
		assertThat(breaker.allowsCall()).isFalse();
	}

	@Test
	void theOpenWindowComesFromConfiguration() {
		MutableClock clock = new MutableClock(NOW);
		RedisCircuitBreaker breaker = new RedisCircuitBreaker(1, Duration.ofMinutes(5), clock);

		breaker.recordFailure();
		assertThat(breaker.allowsCall()).isFalse();

		clock.advance(Duration.ofSeconds(31));
		assertThat(breaker.allowsCall()).as("the default 30s would have reopened by now").isFalse();

		clock.advance(Duration.ofMinutes(5));
		assertThat(breaker.allowsCall()).isTrue();
	}

	@Test
	void aThresholdOfOneOpensImmediately() {
		RedisCircuitBreaker breaker = new RedisCircuitBreaker(1, OPEN_FOR,
				Clock.fixed(NOW, ZoneOffset.UTC));

		breaker.recordFailure();

		assertThat(breaker.allowsCall()).isFalse();
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		private void advance(Duration amount) {
			this.now = this.now.plus(amount);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}
	}
}
