package com.caglar.secure_ticketing_api.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.caglar.secure_ticketing_api.common.resilience.RedisCircuitBreaker;


class ResilientRateLimiter implements RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(ResilientRateLimiter.class);

	private final RateLimiter shared;
	private final RateLimiter local;
	private final RedisCircuitBreaker circuitBreaker;
	private final int instances;

	ResilientRateLimiter(RateLimiter shared, RateLimiter local,
			RedisCircuitBreaker circuitBreaker, int instances) {

		this.shared = shared;
		this.local = local;
		this.circuitBreaker = circuitBreaker;
		this.instances = instances;
	}

	@Override
	public Verdict tryConsume(String key, RateLimitPolicy policy) {
		if (!circuitBreaker.allowsCall()) {
			return locally(key, policy);
		}

		try {
			Verdict verdict = shared.tryConsume(key, policy);
			circuitBreaker.recordSuccess();
			return verdict;
		}
		catch (RuntimeException ex) {
			circuitBreaker.recordFailure();
			log.warn("Shared rate limit counter unavailable, falling back to this instance: {}",
					ex.getMessage());
			return locally(key, policy);
		}
	}

	private Verdict locally(String key, RateLimitPolicy policy) {
		return local.tryConsume(key, policy.dividedBy(instances));
	}
}
