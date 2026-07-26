package com.caglar.secure_ticketing_api.common.resilience;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "resilience.circuit-breaker")
public record CircuitBreakerProperties(int failureThreshold, Duration openDuration) {
}
