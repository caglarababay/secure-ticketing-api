package com.caglar.secure_ticketing_api.reservation.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "reservation.soldout-cache")
public record SoldOutCacheProperties(boolean enabled, Duration ttl) {
}
