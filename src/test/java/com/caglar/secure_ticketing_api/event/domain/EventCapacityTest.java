package com.caglar.secure_ticketing_api.event.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.caglar.secure_ticketing_api.common.error.ApiException;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;


class EventCapacityTest {

	private static final Instant STARTS = Instant.parse("2027-07-01T18:00:00Z");
	private static final Instant ENDS = STARTS.plus(Duration.ofHours(4));

	private Event eventWith(int capacity, int reservedSeats) {
		Event event = new Event(1L, "Concert", "Arena", STARTS, ENDS, capacity);
		ReflectionTestUtils.setField(event, "reservedSeats", reservedSeats);
		return event;
	}

	@Test
	void capacityCanBeRaisedFreely() {
		Event event = eventWith(10, 8);

		event.updateDetails("Bigger", "Hall", STARTS, ENDS, 100);

		assertThat(event.getCapacity()).isEqualTo(100);
		assertThat(event.getAvailableSeats()).isEqualTo(92);
	}

	@Test
	void capacityCanBeReducedDownToTheSeatsAlreadySold() {
		Event event = eventWith(10, 8);

		assertThatCode(() -> event.updateDetails("Exact", "Hall", STARTS, ENDS, 8))
				.doesNotThrowAnyException();

		assertThat(event.getAvailableSeats()).isZero();
	}

	@Test
	void capacityCannotDropBelowTheSeatsAlreadySold() {
		Event event = eventWith(10, 8);

		assertThatThrownBy(() -> event.updateDetails("Too small", "Hall", STARTS, ENDS, 5))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.CAPACITY_BELOW_RESERVED);
	}

	@Test
	void aRejectedReductionChangesNothing() {
		Event event = eventWith(10, 8);

		assertThatThrownBy(() -> event.updateDetails("Too small", "Hall", STARTS, ENDS, 5))
				.isInstanceOf(ApiException.class);

		assertThat(event.getCapacity()).isEqualTo(10);
		assertThat(event.getTitle()).isEqualTo("Concert");
		assertThat(event.getVenue()).isEqualTo("Arena");
	}

	@Test
	void theErrorNamesBothNumbersSoTheCallerCanAct() {
		Event event = eventWith(10, 8);

		assertThatThrownBy(() -> event.updateDetails("Too small", "Hall", STARTS, ENDS, 5))
				.hasMessageContaining("5")
				.hasMessageContaining("8");
	}

	@Test
	void capacityChangeDetectionDrivesCacheInvalidation() {
		Event event = eventWith(10, 0);

		assertThat(event.hasDifferentCapacityThan(20)).isTrue();
		assertThat(event.hasDifferentCapacityThan(10)).isFalse();
	}
}
