package com.caglar.secure_ticketing_api.common.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "ratelimit")
public record RateLimitProperties(boolean enabled, boolean shared, int instances,
		int maxTrackedKeys, Tier auth, Tier reservation) {

	public record Tier(long capacity, Duration window) {
	}

	public RateLimitPolicy authPolicy() {
		return new RateLimitPolicy("auth", auth.capacity(), auth.window());
	}

	public RateLimitPolicy reservationPolicy() {
		return new RateLimitPolicy("reservation", reservation.capacity(), reservation.window());
	}
}
