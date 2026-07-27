package com.caglar.secure_ticketing_api.reservation.api;

import jakarta.validation.constraints.Min;


public record CreateReservationRequest(@Min(1) @SeatsWithinLimit int seats) {
}
