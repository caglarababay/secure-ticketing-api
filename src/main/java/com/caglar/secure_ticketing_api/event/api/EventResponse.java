package com.caglar.secure_ticketing_api.event.api;

import java.time.Instant;

import com.caglar.secure_ticketing_api.event.domain.Event;


public record EventResponse(
		Long id,
		Long ownerId,
		String title,
		String venue,
		Instant startsAt,
		Instant endsAt,
		int capacity,
		boolean published,
		Long version) {

	public static EventResponse from(Event event) {
		return new EventResponse(event.getId(), event.getOwnerId(), event.getTitle(),
				event.getVenue(), event.getStartsAt(), event.getEndsAt(), event.getCapacity(),
				event.isPublished(), event.getVersion());
	}
}
