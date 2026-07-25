package com.caglar.secure_ticketing_api.auth.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret          HMAC signing key (HS256 requires at least 32 bytes)
 * @param accessTokenTtl  short-lived (sent with every request)
 * @param refreshTokenTtl long-lived (only sent to /api/auth/refresh)
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {
}
