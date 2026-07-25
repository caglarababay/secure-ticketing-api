package com.caglar.secure_ticketing_api.idempotency.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "idempotency")
public record IdempotencyProperties(Duration retention, Duration lease,
		Duration sweepInterval, int sweepBatchSize) {
}
