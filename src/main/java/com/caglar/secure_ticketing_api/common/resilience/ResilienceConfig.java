package com.caglar.secure_ticketing_api.common.resilience;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CircuitBreakerProperties.class)
public class ResilienceConfig {
}
