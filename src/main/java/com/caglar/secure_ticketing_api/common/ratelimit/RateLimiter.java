package com.caglar.secure_ticketing_api.common.ratelimit;

import java.time.Duration;

public interface RateLimiter {

	Verdict tryConsume(String key, RateLimitPolicy policy);

	record Verdict(boolean allowed, long remaining, Duration retryAfter) {

		public static Verdict allowed(long remaining) {
			return new Verdict(true, remaining, Duration.ZERO);
		}

		public static Verdict rejected(Duration retryAfter) {
			return new Verdict(false, 0, retryAfter);
		}
	}
}
