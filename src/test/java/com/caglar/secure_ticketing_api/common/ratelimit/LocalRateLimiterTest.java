package com.caglar.secure_ticketing_api.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.caglar.secure_ticketing_api.common.ratelimit.RateLimiter.Verdict;


class LocalRateLimiterTest {

	private static final Instant START = Instant.parse("2026-07-26T10:00:00Z");
	private static final RateLimitPolicy POLICY = new RateLimitPolicy("test", 3, Duration.ofMinutes(1));

	private TickingClock clock;
	private LocalRateLimiter limiter;

	@BeforeEach
	void setUp() {
		clock = new TickingClock(START);
		limiter = new LocalRateLimiter(clock, 1000);
	}

	@Test
	void callsWithinTheBudgetAreAllowed() {
		assertThat(limiter.tryConsume("a", POLICY).allowed()).isTrue();
		assertThat(limiter.tryConsume("a", POLICY).allowed()).isTrue();
		assertThat(limiter.tryConsume("a", POLICY).allowed()).isTrue();
	}

	@Test
	void theCallAfterTheBudgetIsRefused() {
		exhaust("a");

		assertThat(limiter.tryConsume("a", POLICY).allowed()).isFalse();
	}

	@Test
	void remainingCountsDown() {
		assertThat(limiter.tryConsume("a", POLICY).remaining()).isEqualTo(2);
		assertThat(limiter.tryConsume("a", POLICY).remaining()).isEqualTo(1);
		assertThat(limiter.tryConsume("a", POLICY).remaining()).isZero();
	}

	@Test
	void aRefusalSaysHowLongToWait() {
		exhaust("a");

		Verdict verdict = limiter.tryConsume("a", POLICY);

		assertThat(verdict.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1));
	}

	// --- refill ----------------------------------------------------------------

	@Test
	void aTokenReturnsPartWayThroughTheWindow() {
		exhaust("a");
		assertThat(limiter.tryConsume("a", POLICY).allowed()).isFalse();

		clock.advance(Duration.ofSeconds(21));

		assertThat(limiter.tryConsume("a", POLICY).allowed()).isTrue();
	}

	@Test
	void theWholeBudgetIsBackAfterAFullWindow() {
		exhaust("a");

		clock.advance(Duration.ofMinutes(1));

		assertThat(limiter.tryConsume("a", POLICY).allowed()).isTrue();
		assertThat(limiter.tryConsume("a", POLICY).allowed()).isTrue();
		assertThat(limiter.tryConsume("a", POLICY).allowed()).isTrue();
	}

	@Test
	void theBudgetDoesNotGrowBeyondItsCapacity() {
		clock.advance(Duration.ofHours(1));

		exhaust("a");

		assertThat(limiter.tryConsume("a", POLICY).allowed()).isFalse();
	}

	// --- isolation ---------------------------------------------------------------

	@Test
	void callersDoNotSpendEachOthersBudget() {
		exhaust("a");

		assertThat(limiter.tryConsume("b", POLICY).allowed()).isTrue();
	}

	@Test
	void differentPoliciesAreCountedSeparately() {
		RateLimitPolicy other = new RateLimitPolicy("other", 3, Duration.ofMinutes(1));
		exhaust("shared:a");

		assertThat(limiter.tryConsume("other:a", other).allowed()).isTrue();
	}

	// --- bounded memory -------------------------------------------------------------

	@Test
	void repleniShedCallersAreForgottenOnceTheCeilingIsPassed() {
		LocalRateLimiter bounded = new LocalRateLimiter(clock, 10);
		for (int i = 0; i < 50; i++) {
			bounded.tryConsume("caller-" + i, POLICY);
		}

		clock.advance(Duration.ofMinutes(1));
		bounded.tryConsume("trigger", POLICY);

		assertThat(bounded.trackedKeys()).isLessThanOrEqualTo(11);
	}

	@Test
	void aCallerWithSpentBudgetIsNotForgotten() {
		LocalRateLimiter bounded = new LocalRateLimiter(clock, 5);
		bounded.tryConsume("victim", POLICY);
		bounded.tryConsume("victim", POLICY);
		bounded.tryConsume("victim", POLICY);

		for (int i = 0; i < 20; i++) {
			bounded.tryConsume("noise-" + i, POLICY);
		}

		assertThat(bounded.tryConsume("victim", POLICY).allowed())
				.as("the victim's spent budget survived the eviction sweep")
				.isFalse();
	}

	private void exhaust(String key) {
		for (int i = 0; i < POLICY.capacity(); i++) {
			limiter.tryConsume(key, POLICY);
		}
	}

	private static final class TickingClock extends Clock {

		private Instant now;

		private TickingClock(Instant now) {
			this.now = now;
		}

		void advance(Duration amount) {
			now = now.plus(amount);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}
