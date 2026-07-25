package com.caglar.secure_ticketing_api.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.caglar.secure_ticketing_api.common.error.ApiException;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;
import com.caglar.secure_ticketing_api.event.domain.Event;
import com.caglar.secure_ticketing_api.event.domain.EventRepository;
import com.caglar.secure_ticketing_api.reservation.domain.Reservation;
import com.caglar.secure_ticketing_api.reservation.domain.ReservationRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReservationServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");
	private static final Long EVENT_ID = 1L;
	private static final Long USER_ID = 7L;
	private static final Long OTHER_USER_ID = 99L;

	@Mock
	private ReservationRepository reservations;

	@Mock
	private EventRepository events;

	@Mock
	private SoldOutCache soldOutCache;

	/** 15-minute hold, matching the production default. */
	private static final ReservationProperties PROPERTIES =
			new ReservationProperties(Duration.ofMinutes(15), Duration.ofSeconds(60), 200);

	private ReservationService service() {
		return new ReservationService(reservations, events, soldOutCache, PROPERTIES,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private Event event(int capacity, int reservedSeats, boolean published) {
		Event event = new Event(2L, "Concert", "Arena", NOW.plus(Duration.ofDays(30)),
				NOW.plus(Duration.ofDays(30)).plus(Duration.ofHours(3)), capacity);
		if (published) {
			event.publish();
		}
		ReflectionTestUtils.setField(event, "id", EVENT_ID);
		ReflectionTestUtils.setField(event, "reservedSeats", reservedSeats);
		return event;
	}

	private Reservation reservation(Long userId) {
		Reservation reservation = new Reservation(EVENT_ID, userId, 2, NOW, NOW.plusSeconds(900));
		ReflectionTestUtils.setField(reservation, "id", 5L);
		return reservation;
	}

	// --- create -------------------------------------------------------------

	@Test
	void successfulClaimSavesAPendingReservation() {
		when(events.tryReserveSeats(EVENT_ID, 2)).thenReturn(1);
		when(reservations.save(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));

		Reservation created = service().create(EVENT_ID, USER_ID, 2);

		assertThat(created.getStatus().name()).isEqualTo("PENDING");
		assertThat(created.getCreatedAt()).isEqualTo(NOW);
	}

	/** A cached "sold out" must skip the database entirely */
	@Test
	void aCachedSoldOutShortCircuitsBeforeTouchingTheDatabase() {
		when(soldOutCache.isSoldOut(EVENT_ID)).thenReturn(true);

		assertThatThrownBy(() -> service().create(EVENT_ID, USER_ID, 1))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.INSUFFICIENT_CAPACITY);

		verify(events, never()).tryReserveSeats(anyLong(), anyInt());
		verify(reservations, never()).save(any());
	}

	@Test
	void missingEventIsReportedAsNotFound() {
		when(events.tryReserveSeats(anyLong(), anyInt())).thenReturn(0);
		when(events.findById(EVENT_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service().create(EVENT_ID, USER_ID, 1))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}

	@Test
	void unpublishedEventIsRejected() {
		when(events.tryReserveSeats(anyLong(), anyInt())).thenReturn(0);
		when(events.findById(EVENT_ID)).thenReturn(Optional.of(event(10, 0, false)));

		assertThatThrownBy(() -> service().create(EVENT_ID, USER_ID, 1))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.EVENT_NOT_PUBLISHED);
	}

	@Test
	void aTrulyFullEventIsCached() {
		when(events.tryReserveSeats(anyLong(), anyInt())).thenReturn(0);
		when(events.findById(EVENT_ID)).thenReturn(Optional.of(event(10, 10, true)));

		assertThatThrownBy(() -> service().create(EVENT_ID, USER_ID, 1))
				.isInstanceOf(ApiException.class);

		verify(soldOutCache).markSoldOut(EVENT_ID);
	}

	@Test
	void anOversizedRequestDoesNotMarkTheEventSoldOut() {
		when(events.tryReserveSeats(EVENT_ID, 5)).thenReturn(0);
		when(events.findById(EVENT_ID)).thenReturn(Optional.of(event(10, 8, true)));

		assertThatThrownBy(() -> service().create(EVENT_ID, USER_ID, 5))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.INSUFFICIENT_CAPACITY);

		verify(soldOutCache, never()).markSoldOut(anyLong());
	}

	@Test
	void theRemainingSeatCountIsReportedToTheCaller() {
		when(events.tryReserveSeats(EVENT_ID, 5)).thenReturn(0);
		when(events.findById(EVENT_ID)).thenReturn(Optional.of(event(10, 8, true)));

		assertThatThrownBy(() -> service().create(EVENT_ID, USER_ID, 5))
				.hasMessageContaining("Only 2 seat(s) remain");
	}

	// --- confirm / cancel ---------------------------------------------------

	@Test
	void confirmDoesNotTouchCapacity() {
		when(reservations.findById(5L)).thenReturn(Optional.of(reservation(USER_ID)));

		service().confirm(5L, USER_ID, false);

		verify(events, never()).releaseSeats(anyLong(), anyInt());
		verify(events, never()).tryReserveSeats(anyLong(), anyInt());
	}

	@Test
	void cancelReturnsSeatsAndClearsTheCache() {
		when(reservations.findById(5L)).thenReturn(Optional.of(reservation(USER_ID)));
		when(events.releaseSeats(EVENT_ID, 2)).thenReturn(1);

		service().cancel(5L, USER_ID, false);

		verify(events).releaseSeats(EVENT_ID, 2);
		verify(soldOutCache).clear(EVENT_ID);
	}

	@Test
	void aReleaseThatMovesNothingDoesNotTouchTheCache() {
		when(reservations.findById(5L)).thenReturn(Optional.of(reservation(USER_ID)));
		when(events.releaseSeats(EVENT_ID, 2)).thenReturn(0);

		service().cancel(5L, USER_ID, false);

		verify(soldOutCache, never()).clear(anyLong());
	}

	@Test
	void someoneElsesReservationCannotBeConfirmed() {
		when(reservations.findById(5L)).thenReturn(Optional.of(reservation(OTHER_USER_ID)));

		assertThatThrownBy(() -> service().confirm(5L, USER_ID, false))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	@Test
	void adminMayActOnAnyReservation() {
		when(reservations.findById(5L)).thenReturn(Optional.of(reservation(OTHER_USER_ID)));

		assertThat(service().confirm(5L, USER_ID, true).getStatus().name()).isEqualTo("CONFIRMED");
	}

	@Test
	void cancellingAnAlreadyCancelledReservationReleasesNothing() {
		Reservation reservation = reservation(USER_ID);
		reservation.cancel();
		when(reservations.findById(5L)).thenReturn(Optional.of(reservation));

		assertThatThrownBy(() -> service().cancel(5L, USER_ID, false))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);

		verify(events, never()).releaseSeats(anyLong(), anyInt());
	}
}
