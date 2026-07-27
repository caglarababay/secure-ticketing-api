package com.caglar.secure_ticketing_api.reservation.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;


@Validated
@ConfigurationProperties(prefix = "reservation.request")
public record ReservationRequestLimits(@Min(1) int maxSeats) {
}
