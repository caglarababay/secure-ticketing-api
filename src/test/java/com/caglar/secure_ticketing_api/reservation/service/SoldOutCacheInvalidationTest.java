package com.caglar.secure_ticketing_api.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.event.api.UpdateEventRequest;
import com.caglar.secure_ticketing_api.event.domain.Event;
import com.caglar.secure_ticketing_api.event.domain.EventRepository;
import com.caglar.secure_ticketing_api.event.service.EventService;
import com.caglar.secure_ticketing_api.reservation.domain.ReservationRepository;


@SpringBootTest
@ActiveProfiles("test")
class SoldOutCacheInvalidationTest {

	private static final Instant STARTS = Instant.parse("2027-07-01T18:00:00Z");
	private static final Instant ENDS = STARTS.plus(Duration.ofHours(4));

	@MockitoBean
	private SoldOutCache soldOutCache;

	@Autowired
	private EventService eventService;

	@Autowired
	private EventRepository events;

	@Autowired
	private ReservationRepository reservations;

	@Autowired
	private UserRepository users;

	private Long ownerId;
	private Long eventId;

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		events.deleteAll();
		users.deleteAll();

		ownerId = users.save(new User("owner@test.com", "hash",
				EnumSet.of(Role.ORGANIZER), Instant.now())).getId();

		Event event = new Event(ownerId, "Concert", "Arena", STARTS, ENDS, 10);
		event.publish();
		eventId = events.save(event).getId();
	}

	private UpdateEventRequest requestWithCapacity(int capacity) {
		return new UpdateEventRequest("Concert", "Arena", STARTS, ENDS, capacity);
	}

	@Test
	void raisingCapacityClearsTheSoldOutMarker() {
		eventService.update(eventId, requestWithCapacity(50), ownerId, false);

		verify(soldOutCache).clear(eventId);
		assertThat(events.findById(eventId).orElseThrow().getCapacity()).isEqualTo(50);
	}

	@Test
	void loweringCapacityAlsoClearsIt() {
		eventService.update(eventId, requestWithCapacity(5), ownerId, false);

		verify(soldOutCache).clear(eventId);
	}

	@Test
	void anUpdateThatLeavesCapacityAloneDoesNotTouchTheCache() {
		eventService.update(eventId,
				new UpdateEventRequest("Renamed", "New Hall", STARTS, ENDS, 10), ownerId, false);

		verify(soldOutCache, never()).clear(eventId);
		assertThat(events.findById(eventId).orElseThrow().getTitle()).isEqualTo("Renamed");
	}
}
