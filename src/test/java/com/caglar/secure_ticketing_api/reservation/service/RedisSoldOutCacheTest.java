package com.caglar.secure_ticketing_api.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;


@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisSoldOutCacheTest {

	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

	@Mock
	private StringRedisTemplate redis;

	@Mock
	private ValueOperations<String, String> valueOps;

	private RedisSoldOutCache cache(RedisCircuitBreaker breaker) {
		return new RedisSoldOutCache(redis, breaker, Duration.ofSeconds(60));
	}

	private RedisCircuitBreaker breaker() {
		return new RedisCircuitBreaker(Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void reportsSoldOutWhenTheKeyExists() {
		when(redis.hasKey("ticketing:event:7:soldout")).thenReturn(true);

		assertThat(cache(breaker()).isSoldOut(7L)).isTrue();
	}

	@Test
	void reportsNotSoldOutWhenTheKeyIsAbsent() {
		when(redis.hasKey(anyString())).thenReturn(false);

		assertThat(cache(breaker()).isSoldOut(7L)).isFalse();
	}

	@Test
	void aRedisFailureIsReportedAsUnknownRatherThanThrown() {
		when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

		assertThat(cache(breaker()).isSoldOut(7L)).isFalse();
	}

	@Test
	void writeFailuresAreSwallowedToo() {
		when(redis.opsForValue()).thenReturn(valueOps);
		when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));
		org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
				.when(valueOps).set(anyString(), anyString(), any(Duration.class));

		RedisSoldOutCache cache = cache(breaker());

		assertThatCode(() -> cache.markSoldOut(7L)).doesNotThrowAnyException();
		assertThatCode(() -> cache.clear(7L)).doesNotThrowAnyException();
	}

	@Test
	void stopsCallingRedisAfterTheBreakerOpens() {
		when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));
		RedisSoldOutCache cache = cache(breaker());

		// Three failures trip the breaker.
		cache.isSoldOut(1L);
		cache.isSoldOut(1L);
		cache.isSoldOut(1L);
		verify(redis, org.mockito.Mockito.times(3)).hasKey(anyString());

		// Further calls short-circuit without touching Redis.
		cache.isSoldOut(1L);
		cache.isSoldOut(1L);
		verify(redis, org.mockito.Mockito.times(3)).hasKey(anyString());
	}

	@Test
	void writesAreAlsoSkippedWhileTheBreakerIsOpen() {
		RedisCircuitBreaker breaker = breaker();
		breaker.recordFailure();
		breaker.recordFailure();
		breaker.recordFailure();

		cache(breaker).markSoldOut(7L);

		verifyNoInteractions(redis);
	}

	@Test
	void marksSoldOutWithTheConfiguredTtl() {
		when(redis.opsForValue()).thenReturn(valueOps);

		cache(breaker()).markSoldOut(7L);

		verify(valueOps).set("ticketing:event:7:soldout", "1", Duration.ofSeconds(60));
	}

	@Test
	void clearRemovesTheMarker() {
		cache(breaker()).clear(7L);

		verify(redis).delete("ticketing:event:7:soldout");
		verify(redis, never()).hasKey(anyString());
	}
}
