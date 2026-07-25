package com.caglar.secure_ticketing_api.reservation.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;


class RedisSoldOutCache implements SoldOutCache {

	private static final Logger log = LoggerFactory.getLogger(RedisSoldOutCache.class);

	private static final String KEY_PREFIX = "ticketing:event:";
	private static final String KEY_SUFFIX = ":soldout";

	private final StringRedisTemplate redis;
	private final RedisCircuitBreaker circuitBreaker;
	private final Duration ttl;

	RedisSoldOutCache(StringRedisTemplate redis, RedisCircuitBreaker circuitBreaker, Duration ttl) {
		this.redis = redis;
		this.circuitBreaker = circuitBreaker;
		this.ttl = ttl;
	}

	@Override
	public boolean isSoldOut(Long eventId) {
		return withCache(() -> Boolean.TRUE.equals(redis.hasKey(key(eventId))), false);
	}

	@Override
	public void markSoldOut(Long eventId) {
		withCache(() -> {
			redis.opsForValue().set(key(eventId), "1", ttl);
			return null;
		}, null);
	}

	@Override
	public void clear(Long eventId) {
		withCache(() -> redis.delete(key(eventId)), null);
	}

	private <T> T withCache(CacheCall<T> call, T fallback) {
		if (!circuitBreaker.allowsCall()) {
			return fallback;
		}
		try {
			T result = call.run();
			circuitBreaker.recordSuccess();
			return result;
		}
		catch (RuntimeException ex) {
			circuitBreaker.recordFailure();
			log.warn("Sold-out cache unavailable, continuing without it: {}", ex.getMessage());
			return fallback;
		}
	}

	private String key(Long eventId) {
		return KEY_PREFIX + eventId + KEY_SUFFIX;
	}

	@FunctionalInterface
	private interface CacheCall<T> {

		T run();
	}
}
