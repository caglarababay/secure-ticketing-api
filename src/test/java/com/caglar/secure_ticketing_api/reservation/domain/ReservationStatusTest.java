package com.caglar.secure_ticketing_api.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.caglar.secure_ticketing_api.common.error.ApiException;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;


class ReservationStatusTest {

	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

	private Reservation reservation() {
		return new Reservation(1L, 2L, 3, NOW, NOW.plusSeconds(900));
	}

	@Test
	void aNewReservationStartsPendingAndHoldsSeats() {
		Reservation reservation = reservation();

		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
		assertThat(reservation.holdsSeats()).isTrue();
	}

	@Test
	void pendingCanBeConfirmed() {
		Reservation reservation = reservation();

		reservation.confirm();

		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(reservation.holdsSeats()).isTrue();
	}

	@Test
	void pendingCanBeCancelled() {
		Reservation reservation = reservation();

		reservation.cancel();

		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
		assertThat(reservation.holdsSeats()).isFalse();
	}

	@Test
	void confirmedCanBeCancelled() {
		Reservation reservation = reservation();
		reservation.confirm();

		reservation.cancel();

		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
	}

	@Test
	void cancelledCannotBeConfirmed() {
		Reservation reservation = reservation();
		reservation.cancel();

		assertThatThrownBy(reservation::confirm)
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);

		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
	}

	@Test
	void confirmingTwiceIsRejected() {
		Reservation reservation = reservation();
		reservation.confirm();

		assertThatThrownBy(reservation::confirm)
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
	}

	@Test
	void cancellingTwiceIsRejected() {
		Reservation reservation = reservation();
		reservation.cancel();

		assertThatThrownBy(reservation::cancel)
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
	}

	@Test
	void ownershipIsCheckedAgainstTheReservingUser() {
		Reservation reservation = reservation();

		assertThat(reservation.isOwnedBy(2L)).isTrue();
		assertThat(reservation.isOwnedBy(99L)).isFalse();
	}
}
