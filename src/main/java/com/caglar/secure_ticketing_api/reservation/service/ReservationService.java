package com.caglar.secure_ticketing_api.reservation.service;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.caglar.secure_ticketing_api.audit.domain.AuditAction;
import com.caglar.secure_ticketing_api.audit.domain.AuditResource;
import com.caglar.secure_ticketing_api.audit.service.Audited;
import com.caglar.secure_ticketing_api.common.error.ApiException;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;
import com.caglar.secure_ticketing_api.event.domain.Event;
import com.caglar.secure_ticketing_api.event.domain.EventRepository;
import com.caglar.secure_ticketing_api.reservation.domain.Reservation;
import com.caglar.secure_ticketing_api.reservation.domain.ReservationRepository;

@Service
public class ReservationService {

	private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

	private final ReservationRepository reservations;
	private final EventRepository events;
	private final SoldOutCache soldOutCache;
	private final ReservationProperties properties;
	private final Clock clock;

	ReservationService(ReservationRepository reservations, EventRepository events,
			SoldOutCache soldOutCache, ReservationProperties properties, Clock clock) {

		this.reservations = reservations;
		this.events = events;
		this.soldOutCache = soldOutCache;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	@Audited(action = AuditAction.RESERVATION_CREATED, resource = AuditResource.RESERVATION)
	public Reservation create(Long eventId, Long userId, int seats) {
		if (soldOutCache.isSoldOut(eventId)) {
			throw new ApiException(ErrorCode.INSUFFICIENT_CAPACITY,
					"This event has no remaining capacity.");
		}

		if (events.tryReserveSeats(eventId, seats) == 0) {
			throw explainFailedClaim(eventId);
		}

		Instant now = Instant.now(clock);
		return reservations.save(new Reservation(eventId, userId, seats,
				now, now.plus(properties.holdTtl())));
	}

	@Transactional
	@Audited(action = AuditAction.RESERVATION_CONFIRMED, resource = AuditResource.RESERVATION)
	public Reservation confirm(Long id, Long callerId, boolean callerIsAdmin) {
		Reservation reservation = require(id);
		requireOwnerOrAdmin(reservation, callerId, callerIsAdmin);

		reservation.confirm();
		return reservation;
	}

	@Transactional
	@Audited(action = AuditAction.RESERVATION_CANCELLED, resource = AuditResource.RESERVATION)
	public Reservation cancel(Long id, Long callerId, boolean callerIsAdmin) {
		Reservation reservation = require(id);
		requireOwnerOrAdmin(reservation, callerId, callerIsAdmin);

		reservation.cancel();
		releaseSeatsFor(reservation);

		return reservation;
	}

	private void releaseSeatsFor(Reservation reservation) {
		if (events.releaseSeats(reservation.getEventId(), reservation.getSeats()) == 1) {
			soldOutCache.clear(reservation.getEventId());
		}
		else {
			log.error("Releasing {} seat(s) for event {} matched no row; "
					+ "reserved_seats may have drifted below the reserved total",
					reservation.getSeats(), reservation.getEventId());
		}
	}

	private ApiException explainFailedClaim(Long eventId) {
		Event event = events.findById(eventId).orElse(null);
		if (event == null) {
			return new ApiException(ErrorCode.NOT_FOUND, "Event not found.");
		}
		if (!event.isPublished()) {
			return new ApiException(ErrorCode.EVENT_NOT_PUBLISHED,
					"This event is not published yet.");
		}

		if (event.getAvailableSeats() <= 0) {
			soldOutCache.markSoldOut(eventId);
		}

		return new ApiException(ErrorCode.INSUFFICIENT_CAPACITY,
				"Only %d seat(s) remain for this event.".formatted(event.getAvailableSeats()));
	}

	private Reservation require(Long id) {
		return reservations.findById(id)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Reservation not found."));
	}

	private void requireOwnerOrAdmin(Reservation reservation, Long callerId, boolean callerIsAdmin) {
		if (!callerIsAdmin && !reservation.isOwnedBy(callerId)) {
			throw new ApiException(ErrorCode.FORBIDDEN, "This reservation belongs to someone else.");
		}
	}
}
