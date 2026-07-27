package com.caglar.secure_ticketing_api.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.caglar.secure_ticketing_api.common.ratelimit.RateLimiter.Verdict;

/**
 * Shared counting is switched on and pointed at a port nothing is listening on.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = { "ratelimit.shared=true", "spring.data.redis.port=6399" })
class RateLimitStartsWithoutRedisTest {

	private static final RateLimitPolicy POLICY =
			new RateLimitPolicy("startup-probe", 2, Duration.ofMinutes(1));

	@Autowired
	private RateLimiter rateLimiter;

	@Test
	void theApplicationStartsWithSharedCountingOnAndRedisAway() {
		assertThat(rateLimiter).isInstanceOf(ResilientRateLimiter.class);
	}

	@Test
	void theLimitIsStillEnforced() {
		String key = "no-redis-here";

		assertThat(rateLimiter.tryConsume(key, POLICY).allowed()).isTrue();
		assertThat(rateLimiter.tryConsume(key, POLICY).allowed()).isTrue();

		Verdict third = rateLimiter.tryConsume(key, POLICY);

		assertThat(third.allowed()).as("a budget of two must not admit a third").isFalse();
		assertThat(third.retryAfter()).isPositive();
	}
}
