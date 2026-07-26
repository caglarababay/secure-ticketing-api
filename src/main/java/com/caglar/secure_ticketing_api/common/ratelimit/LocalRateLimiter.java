package com.caglar.secure_ticketing_api.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.local.LocalBucket;

/**
 * Counts requests inside this JVM.
 */
class LocalRateLimiter implements RateLimiter {

	private final Map<String, Tracked> buckets = new ConcurrentHashMap<>();
	private final TimeMeter timeMeter;
	private final int maxTrackedKeys;

	LocalRateLimiter(Clock clock, int maxTrackedKeys) {
		this.timeMeter = new ClockTimeMeter(clock);
		this.maxTrackedKeys = maxTrackedKeys;
	}

	@Override
	public Verdict tryConsume(String key, RateLimitPolicy policy) {
		Tracked tracked = buckets.computeIfAbsent(key, ignored -> newBucket(policy));
		ConsumptionProbe probe = tracked.bucket().tryConsumeAndReturnRemaining(1);

		if (buckets.size() > maxTrackedKeys) {
			evictReplenished();
		}

		return probe.isConsumed()
				? Verdict.allowed(probe.getRemainingTokens())
				: Verdict.rejected(Duration.ofNanos(probe.getNanosToWaitForRefill()));
	}

	int trackedKeys() {
		return buckets.size();
	}

	private Tracked newBucket(RateLimitPolicy policy) {
		LocalBucket bucket = Bucket.builder()
				.addLimit(policy.toBucketConfiguration().getBandwidths()[0])
				.withCustomTimePrecision(timeMeter)
				.build();

		return new Tracked(bucket, policy.capacity());
	}

	private void evictReplenished() {
		buckets.entrySet().removeIf(entry ->
				entry.getValue().bucket().getAvailableTokens() >= entry.getValue().capacity());
	}

	private record Tracked(LocalBucket bucket, long capacity) {
	}

	private record ClockTimeMeter(Clock clock) implements TimeMeter {

		@Override
		public long currentTimeNanos() {
			return TimeUnit.MILLISECONDS.toNanos(clock.millis());
		}

		@Override
		public boolean isWallClockBased() {
			return true;
		}
	}
}
