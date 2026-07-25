package com.caglar.secure_ticketing_api.reservation.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "reservation.hold")
public record ReservationProperties(Duration holdTtl, Duration sweepInterval, int sweepBatchSize) {
}
