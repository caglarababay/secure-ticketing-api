package com.caglar.secure_ticketing_api.reservation.service;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.caglar.secure_ticketing_api.common.resilience.CircuitBreakerProperties;
import com.caglar.secure_ticketing_api.common.resilience.RedisCircuitBreaker;
import com.caglar.secure_ticketing_api.reservation.api.ReservationRequestLimits;


@Configuration
@EnableScheduling
@EnableConfigurationProperties({ SoldOutCacheProperties.class, ReservationProperties.class,
		ReservationRequestLimits.class })
class ReservationConfig {

	@Bean
	SoldOutCache soldOutCache(SoldOutCacheProperties properties,
			CircuitBreakerProperties breakerProperties,
			StringRedisTemplate redisTemplate, Clock clock) {

		if (!properties.enabled()) {
			return new NoOpSoldOutCache();
		}
		
		return new RedisSoldOutCache(redisTemplate,
				new RedisCircuitBreaker(breakerProperties, clock), properties.ttl());
	}
}
