package com.caglar.secure_ticketing_api.idempotency.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfig {
}
