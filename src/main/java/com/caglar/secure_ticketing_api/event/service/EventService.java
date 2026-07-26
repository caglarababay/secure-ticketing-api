package com.caglar.secure_ticketing_api.event.service;

import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.caglar.secure_ticketing_api.audit.domain.AuditAction;
import com.caglar.secure_ticketing_api.audit.domain.AuditResource;
import com.caglar.secure_ticketing_api.audit.service.Audited;
import com.caglar.secure_ticketing_api.common.error.ApiException;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;
import com.caglar.secure_ticketing_api.event.api.CreateEventRequest;
import com.caglar.secure_ticketing_api.event.api.UpdateEventRequest;
import com.caglar.secure_ticketing_api.event.domain.Event;
import com.caglar.secure_ticketing_api.event.domain.EventCapacityChanged;
import com.caglar.secure_ticketing_api.event.domain.EventRepository;
import com.caglar.secure_ticketing_api.event.domain.EventSpecifications;

@Service
public class EventService {

	private final EventRepository events;
	private final ApplicationEventPublisher publisher;

	EventService(EventRepository events, ApplicationEventPublisher publisher) {
		this.events = events;
		this.publisher = publisher;
	}

	@Transactional
	public Event create(CreateEventRequest request, Long ownerId) {
		Event event = new Event(ownerId, request.title(), request.venue(),
				request.startsAt(), request.endsAt(), request.capacity());
		return events.save(event);
	}

	@Transactional
	public Event update(Long id, UpdateEventRequest request, Long callerId, boolean callerIsAdmin) {
		Event event = require(id);
		requireOwnerOrAdmin(event, callerId, callerIsAdmin);

		boolean capacityChanged = event.hasDifferentCapacityThan(request.capacity());

		event.updateDetails(request.title(), request.venue(), request.startsAt(),
				request.endsAt(), request.capacity());

		if (capacityChanged) {
			publisher.publishEvent(new EventCapacityChanged(id));
		}

		return event;
	}

	@Transactional
	@Audited(action = AuditAction.EVENT_PUBLISHED, resource = AuditResource.EVENT)
	public Event publish(Long id, Long callerId, boolean callerIsAdmin) {
		Event event = require(id);
		requireOwnerOrAdmin(event, callerId, callerIsAdmin);

		event.publish();
		return event;
	}

	@Transactional(readOnly = true)
	public Page<Event> list(Long ownerId, Pageable pageable) {
		return events.findAll(EventSpecifications.ownedBy(ownerId), pageable);
	}

	@Transactional(readOnly = true)
	public Page<Event> discoverPublished(Instant from, Instant to, String q, Pageable pageable) {
		Specification<Event> spec = Specification.allOf(
				EventSpecifications.publishedOnly(),
				EventSpecifications.startingAtOrAfter(from),
				EventSpecifications.startingAtOrBefore(to),
				EventSpecifications.titleContains(q));

		return events.findAll(spec, pageable);
	}

	private Event require(Long id) {
		return events.findById(id)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Event not found."));
	}

	private void requireOwnerOrAdmin(Event event, Long callerId, boolean callerIsAdmin) {
		if (!callerIsAdmin && !event.isOwnedBy(callerId)) {
			throw new ApiException(ErrorCode.FORBIDDEN, "You do not own this event.");
		}
	}
}
