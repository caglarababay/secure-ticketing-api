package com.caglar.secure_ticketing_api.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RateLimitPolicyTest {

	private static final Duration MINUTE = Duration.ofMinutes(1);

	@Test
	void aPolicyBecomesABucketOfItsCapacity() {
		RateLimitPolicy policy = new RateLimitPolicy("auth", 10, MINUTE);

		assertThat(policy.toBucketConfiguration().getBandwidths()[0].getCapacity()).isEqualTo(10);
	}

	@Test
	void aPolicyThatAllowsNothingIsRejected() {
		assertThatThrownBy(() -> new RateLimitPolicy("auth", 0, MINUTE))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// --- splitting the budget across instances --------------------------------

	@ParameterizedTest(name = "{0} across {1} instances -> {2}")
	@CsvSource({
			"100, 1, 100",
			"100, 2, 50",
			"100, 4, 25",
			"100, 3, 33" })
	void theBudgetIsSharedOutBetweenInstances(long capacity, int instances, long expected) {
		RateLimitPolicy split = new RateLimitPolicy("auth", capacity, MINUTE).dividedBy(instances);

		assertThat(split.capacity()).isEqualTo(expected);
	}

	@Test
	void aSingleInstanceKeepsTheWholeBudget() {
		RateLimitPolicy policy = new RateLimitPolicy("auth", 10, MINUTE);

		assertThat(policy.dividedBy(1)).isSameAs(policy);
		assertThat(policy.dividedBy(0)).isSameAs(policy);
	}

	@Test
	void theShareNeverFallsToNothing() {
		assertThat(new RateLimitPolicy("auth", 3, MINUTE).dividedBy(10).capacity()).isEqualTo(1);
		assertThat(new RateLimitPolicy("auth", 1, MINUTE).dividedBy(100).capacity()).isEqualTo(1);
	}

	@Test
	void splittingKeepsTheNameAndWindow() {
		RateLimitPolicy split = new RateLimitPolicy("auth", 100, MINUTE).dividedBy(4);

		assertThat(split.name()).isEqualTo("auth");
		assertThat(split.window()).isEqualTo(MINUTE);
	}
}
