package com.caglar.secure_ticketing_api.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.event.domain.Event;
import com.caglar.secure_ticketing_api.event.domain.EventRepository;
import com.caglar.secure_ticketing_api.reservation.domain.Reservation;
import com.caglar.secure_ticketing_api.reservation.domain.ReservationRepository;
import com.caglar.secure_ticketing_api.reservation.domain.ReservationStatus;


@SpringBootTest
@ActiveProfiles("test")
class ExpiredHoldSweeperTest {

	private static final Instant STARTS = Instant.parse("2027-07-01T18:00:00Z");
	private static final Instant ENDS = STARTS.plus(Duration.ofHours(4));
	private static final int CAPACITY = 10;

	@Autowired
	private ExpiredHoldSweeper sweeper;

	@Autowired
	private ReservationRepository reservations;

	@Autowired
	private EventRepository events;

	@Autowired
	private UserRepository users;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private Long eventId;
	private Long userId;

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		events.deleteAll();
		users.deleteAll();

		userId = users.save(new User("holder@test.com", "hash",
				EnumSet.of(Role.CUSTOMER), Instant.now())).getId();

		Event event = new Event(userId, "Concert", "Arena", STARTS, ENDS, CAPACITY);
		event.publish();
		eventId = events.save(event).getId();
	}

	private Reservation holdExpiringAt(Instant expiresAt, int seats) {
		return transactionTemplate.execute(status -> {
			events.tryReserveSeats(eventId, seats);
			return reservations.save(new Reservation(eventId, userId, seats, Instant.now(), expiresAt));
		});
	}

	private int reservedSeats() {
		return events.findById(eventId).orElseThrow().getReservedSeats();
	}

	@Test
	void anExpiredHoldIsCancelledAndItsSeatsReturned() {
		Long id = holdExpiringAt(Instant.now().minus(Duration.ofMinutes(1)), 4).getId();
		assertThat(reservedSeats()).isEqualTo(4);

		sweeper.sweep();

		assertThat(reservations.findById(id).orElseThrow().getStatus())
				.isEqualTo(ReservationStatus.CANCELLED);
		assertThat(reservedSeats()).isZero();
	}

	@Test
	void aHoldThatHasNotExpiredIsLeftAlone() {
		Long id = holdExpiringAt(Instant.now().plus(Duration.ofMinutes(15)), 4).getId();

		sweeper.sweep();

		assertThat(reservations.findById(id).orElseThrow().getStatus())
				.isEqualTo(ReservationStatus.PENDING);
		assertThat(reservedSeats()).isEqualTo(4);
	}

	@Test
	void aConfirmedReservationIsNeverSwept() {
		Reservation hold = holdExpiringAt(Instant.now().minus(Duration.ofMinutes(1)), 4);
		hold.confirm();
		reservations.save(hold);

		sweeper.sweep();

		Reservation reloaded = reservations.findById(hold.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(reloaded.getExpiresAt()).isNull();
		assertThat(reservedSeats()).isEqualTo(4);
	}

	@Test
	void sweepingTwiceDoesNotReleaseTheSameSeatsAgain() {
		holdExpiringAt(Instant.now().minus(Duration.ofMinutes(1)), 6);

		sweeper.sweep();
		assertThat(reservedSeats()).isZero();

		sweeper.sweep();

		assertThat(reservedSeats()).isZero();
		assertThat(reservations.sumActiveSeats(eventId)).isZero();
	}

	@Test
	void reclaimedSeatsBecomeAvailableAgain() {
		holdExpiringAt(Instant.now().minus(Duration.ofMinutes(1)), CAPACITY);
		assertThat(reservedSeats()).isEqualTo(CAPACITY);

		sweeper.sweep();

		Integer claimed = transactionTemplate.execute(status -> events.tryReserveSeats(eventId, CAPACITY));
		assertThat(claimed).isEqualTo(1);
	}

	@Test
	void severalExpiredHoldsAreAllReclaimed() {
		holdExpiringAt(Instant.now().minus(Duration.ofMinutes(5)), 3);
		holdExpiringAt(Instant.now().minus(Duration.ofMinutes(3)), 3);
		holdExpiringAt(Instant.now().plus(Duration.ofMinutes(10)), 2);
		assertThat(reservedSeats()).isEqualTo(8);

		sweeper.sweep();

		assertThat(reservedSeats()).isEqualTo(2);
		assertThat(reservations.sumActiveSeats(eventId)).isEqualTo(2);
	}

	@Test
	void anEmptySweepIsHarmless() {
		sweeper.sweep();

		assertThat(reservedSeats()).isZero();
	}

	@Test
	void integrityHoldsAfterSweeping() {
		holdExpiringAt(Instant.now().minus(Duration.ofMinutes(1)), 4);
		holdExpiringAt(Instant.now().plus(Duration.ofMinutes(10)), 3);

		sweeper.sweep();

		assertThat(reservedSeats()).isEqualTo(reservations.sumActiveSeats(eventId));
	}
}
