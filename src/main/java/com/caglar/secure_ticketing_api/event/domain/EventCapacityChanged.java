package com.caglar.secure_ticketing_api.event.domain;

/**
 * Published when an event's seat count changes.
 */
public record EventCapacityChanged(Long eventId) {
}
