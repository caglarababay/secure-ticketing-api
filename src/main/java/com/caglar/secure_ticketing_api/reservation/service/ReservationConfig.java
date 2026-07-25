package com.caglar.secure_ticketing_api.reservation.service;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;


@Configuration
@EnableScheduling
@EnableConfigurationProperties({ SoldOutCacheProperties.class, ReservationProperties.class })
class ReservationConfig {

	@Bean
	SoldOutCache soldOutCache(SoldOutCacheProperties properties,
			StringRedisTemplate redisTemplate, Clock clock) {

		if (!properties.enabled()) {
			return new NoOpSoldOutCache();
		}
		return new RedisSoldOutCache(redisTemplate, new RedisCircuitBreaker(clock), properties.ttl());
	}
}
