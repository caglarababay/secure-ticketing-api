package com.caglar.secure_ticketing_api.common.ratelimit;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;

/**
 * Counts requests in Redis, so every instance sees the same budget.
 */
class DistributedRateLimiter implements RateLimiter {

	private final Supplier<ProxyManager<byte[]>> connect;

	private volatile ProxyManager<byte[]> proxyManager;

	DistributedRateLimiter(Supplier<ProxyManager<byte[]>> connect) {
		this.connect = connect;
	}

	@Override
	public Verdict tryConsume(String key, RateLimitPolicy policy) {
		BucketProxy bucket = proxyManager().builder()
				.build(key.getBytes(StandardCharsets.UTF_8), policy::toBucketConfiguration);

		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

		return probe.isConsumed()
				? Verdict.allowed(probe.getRemainingTokens())
				: Verdict.rejected(Duration.ofNanos(probe.getNanosToWaitForRefill()));
	}

	private ProxyManager<byte[]> proxyManager() {
		ProxyManager<byte[]> connected = this.proxyManager;
		if (connected != null) {
			return connected;
		}

		synchronized (this) {
			if (this.proxyManager == null) {
				this.proxyManager = connect.get();
			}
			return this.proxyManager;
		}
	}
}
