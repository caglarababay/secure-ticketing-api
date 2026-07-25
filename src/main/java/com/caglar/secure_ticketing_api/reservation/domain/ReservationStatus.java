package com.caglar.secure_ticketing_api.reservation.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;


public enum ReservationStatus {

	PENDING,
	CONFIRMED,
	CANCELLED;

	private static final Map<ReservationStatus, Set<ReservationStatus>> ALLOWED_TRANSITIONS = Map.of(
			PENDING, EnumSet.of(CONFIRMED, CANCELLED),
			CONFIRMED, EnumSet.of(CANCELLED),
			CANCELLED, EnumSet.noneOf(ReservationStatus.class));

	public boolean canTransitionTo(ReservationStatus target) {
		return ALLOWED_TRANSITIONS.get(this).contains(target);
	}

	/** PENDING and CONFIRMED occupy capacity; CANCELLED releases it. */
	public boolean holdsSeats() {
		return this != CANCELLED;
	}

	public boolean isTerminal() {
		return ALLOWED_TRANSITIONS.get(this).isEmpty();
	}
}
