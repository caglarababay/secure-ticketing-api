package com.caglar.secure_ticketing_api.reservation.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;


public record CreateReservationRequest(@Positive @Max(50) int seats) {
}
