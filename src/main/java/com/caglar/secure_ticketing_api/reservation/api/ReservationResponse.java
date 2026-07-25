package com.caglar.secure_ticketing_api.reservation.api;

import java.time.Instant;

import com.caglar.secure_ticketing_api.reservation.domain.Reservation;
import com.caglar.secure_ticketing_api.reservation.domain.ReservationStatus;

public record ReservationResponse(
		Long id,
		Long eventId,
		Long userId,
		ReservationStatus status,
		int seats,
		Instant createdAt,
		Long version) {

	public static ReservationResponse from(Reservation reservation) {
		return new ReservationResponse(reservation.getId(), reservation.getEventId(),
				reservation.getUserId(), reservation.getStatus(), reservation.getSeats(),
				reservation.getCreatedAt(), reservation.getVersion());
	}
}
