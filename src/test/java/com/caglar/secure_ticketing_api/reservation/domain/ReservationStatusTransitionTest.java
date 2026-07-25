package com.caglar.secure_ticketing_api.reservation.domain;

import static com.caglar.secure_ticketing_api.reservation.domain.ReservationStatus.CANCELLED;
import static com.caglar.secure_ticketing_api.reservation.domain.ReservationStatus.CONFIRMED;
import static com.caglar.secure_ticketing_api.reservation.domain.ReservationStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;


class ReservationStatusTransitionTest {

	@ParameterizedTest(name = "{0} -> {1} allowed: {2}")
	@CsvSource({
			"PENDING,   CONFIRMED, true",
			"PENDING,   CANCELLED, true",
			"PENDING,   PENDING,   false",
			"CONFIRMED, CANCELLED, true",
			"CONFIRMED, CONFIRMED, false",
			"CONFIRMED, PENDING,   false",
			"CANCELLED, CONFIRMED, false",
			"CANCELLED, CANCELLED, false",
			"CANCELLED, PENDING,   false" })
	void theTransitionTableIsExhaustivelyPinned(ReservationStatus from, ReservationStatus to,
			boolean allowed) {

		assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
	}

	@Test
	void cancelledCanNeverBecomeConfirmed() {
		assertThat(CANCELLED.canTransitionTo(CONFIRMED)).isFalse();
		assertThat(CANCELLED.isTerminal()).isTrue();
	}

	@Test
	void noStateCanReturnToPending() {
		assertThat(CONFIRMED.canTransitionTo(PENDING)).isFalse();
		assertThat(CANCELLED.canTransitionTo(PENDING)).isFalse();
	}

	@Test
	void repeatingTheCurrentStateIsNeverALegalMove() {
		for (ReservationStatus status : ReservationStatus.values()) {
			assertThat(status.canTransitionTo(status))
					.as("%s -> %s", status, status)
					.isFalse();
		}
	}

	@Test
	void onlyCancelledIsTerminal() {
		assertThat(PENDING.isTerminal()).isFalse();
		assertThat(CONFIRMED.isTerminal()).isFalse();
		assertThat(CANCELLED.isTerminal()).isTrue();
	}

	// --- capacity -----------------------------------------------------------

	@Test
	void pendingAndConfirmedBothOccupyCapacity() {
		assertThat(PENDING.holdsSeats()).isTrue();
		assertThat(CONFIRMED.holdsSeats()).isTrue();
	}

	@Test
	void cancelledReleasesCapacity() {
		assertThat(CANCELLED.holdsSeats()).isFalse();
	}

	@ParameterizedTest
	@EnumSource(ReservationStatus.class)
	void everyStateAnswersBothQuestionsWithoutFailing(ReservationStatus status) {
		assertThat(status.holdsSeats()).isIn(true, false);
		assertThat(status.isTerminal()).isIn(true, false);
	}
}
