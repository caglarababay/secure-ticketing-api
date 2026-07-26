package com.caglar.secure_ticketing_api.common.ratelimit;

import java.time.Duration;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;


public record RateLimitPolicy(String name, long capacity, Duration window) {

	public RateLimitPolicy {
		if (capacity < 1) {
			throw new IllegalArgumentException("A rate limit policy needs a capacity of at least 1");
		}
	}

	public BucketConfiguration toBucketConfiguration() {
		Bandwidth bandwidth = Bandwidth.builder()
				.capacity(capacity)
				.refillGreedy(capacity, window)
				.build();

		return BucketConfiguration.builder().addLimit(bandwidth).build();
	}

	public RateLimitPolicy dividedBy(int instances) {
		if (instances <= 1) {
			return this;
		}
		return new RateLimitPolicy(name, Math.max(1, capacity / instances), window);
	}
}
