package com.caglar.secure_ticketing_api.reservation.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.caglar.secure_ticketing_api.event.domain.EventRepository;
import com.caglar.secure_ticketing_api.reservation.domain.Reservation;
import com.caglar.secure_ticketing_api.reservation.domain.ReservationRepository;

/**
 * Returns seats held by reservations that were never confirmed.
 */
@Component
class ExpiredHoldSweeper {

	private static final Logger log = LoggerFactory.getLogger(ExpiredHoldSweeper.class);

	private final ReservationRepository reservations;
	private final EventRepository events;
	private final SoldOutCache soldOutCache;
	private final ReservationProperties properties;
	private final Clock clock;

	ExpiredHoldSweeper(ReservationRepository reservations, EventRepository events,
			SoldOutCache soldOutCache, ReservationProperties properties, Clock clock) {

		this.reservations = reservations;
		this.events = events;
		this.soldOutCache = soldOutCache;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${reservation.hold.sweep-interval}")
	@Transactional
	public void sweep() {
		Instant now = Instant.now(clock);
		List<Reservation> expired = reservations.findExpiredHolds(
				now, PageRequest.of(0, properties.sweepBatchSize()));

		if (expired.isEmpty()) {
			return;
		}

		int reclaimed = 0;
		for (Reservation hold : expired) {
			if (reservations.cancelIfStillPending(hold.getId()) == 1) {
				if (events.releaseSeats(hold.getEventId(), hold.getSeats()) == 1) {
					soldOutCache.clear(hold.getEventId());
				}
				reclaimed++;
			}
		}

		if (reclaimed > 0) {
			log.info("Reclaimed {} expired reservation hold(s)", reclaimed);
		}
	}
}
