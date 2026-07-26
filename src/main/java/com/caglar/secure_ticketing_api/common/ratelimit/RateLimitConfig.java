package com.caglar.secure_ticketing_api.common.ratelimit;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import com.caglar.secure_ticketing_api.common.resilience.CircuitBreakerProperties;
import com.caglar.secure_ticketing_api.common.resilience.RedisCircuitBreaker;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfig {

	@Bean
	RateLimiter rateLimiter(RateLimitProperties properties,
			CircuitBreakerProperties breakerProperties,
			LettuceConnectionFactory connectionFactory, Clock clock) {

		LocalRateLimiter local = new LocalRateLimiter(clock, properties.maxTrackedKeys());
		if (!properties.shared()) {
			return local;
		}

		DistributedRateLimiter shared = new DistributedRateLimiter(sharedCounters(connectionFactory));
		return new ResilientRateLimiter(shared, local,
				new RedisCircuitBreaker(breakerProperties, clock), properties.instances());
	}

	private io.github.bucket4j.distributed.proxy.ProxyManager<byte[]> sharedCounters(
			LettuceConnectionFactory connectionFactory) {

		RedisClient client = (RedisClient) connectionFactory.getRequiredNativeClient();

		return Bucket4jLettuce.casBasedBuilder(client)
				.expirationAfterWrite(ExpirationAfterWriteStrategy
						.basedOnTimeForRefillingBucketUpToMax(java.time.Duration.ZERO))
				.build();
	}
}
