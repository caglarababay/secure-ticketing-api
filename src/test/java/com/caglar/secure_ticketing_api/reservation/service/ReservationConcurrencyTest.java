package com.caglar.secure_ticketing_api.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.common.error.ApiException;
import com.caglar.secure_ticketing_api.event.domain.Event;
import com.caglar.secure_ticketing_api.event.domain.EventRepository;
import com.caglar.secure_ticketing_api.reservation.domain.ReservationRepository;


@SpringBootTest
@ActiveProfiles("test")
class ReservationConcurrencyTest {

	private static final int CAPACITY = 10;
	private static final int CONTENDERS = 20;

	@Autowired
	private ReservationService reservationService;

	@Autowired
	private EventRepository events;

	@Autowired
	private ReservationRepository reservations;

	@Autowired
	private UserRepository users;

	private Long eventId;
	private Long userId;

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		events.deleteAll();
		users.deleteAll();

		userId = users.save(new User("racer@test.com", "hash",
				EnumSet.of(Role.CUSTOMER), Instant.now())).getId();

		Event event = new Event(userId, "Sold Out Soon", "Arena",
				Instant.parse("2027-07-01T18:00:00Z"), Instant.parse("2027-07-01T22:00:00Z"), CAPACITY);
		event.publish();
		eventId = events.save(event).getId();
	}

	/**
	 * 20 threads each ask for one seat on a 10-seat event
	 */
	@Test
	void concurrentRequestsNeverOversellTheEvent() throws Exception {
		AtomicInteger succeeded = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		CountDownLatch startLine = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(CONTENDERS);

		try (ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS)) {
			for (int i = 0; i < CONTENDERS; i++) {
				pool.submit(() -> {
					try {
						startLine.await();
						reservationService.create(eventId, userId, 1);
						succeeded.incrementAndGet();
					}
					catch (ApiException ex) {
						rejected.incrementAndGet();
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
					finally {
						finished.countDown();
					}
				});
			}

			startLine.countDown();
			assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(succeeded.get()).isEqualTo(CAPACITY);
		assertThat(rejected.get()).isEqualTo(CONTENDERS - CAPACITY);

		
		assertThat(events.findById(eventId).orElseThrow().getReservedSeats()).isEqualTo(CAPACITY);
		
		assertThat(reservations.sumActiveSeats(eventId)).isEqualTo(CAPACITY);
		assertThat(reservations.count()).isEqualTo(CAPACITY);
	}

	@Test
	void concurrentMultiSeatRequestsRespectCapacity() throws Exception {
		AtomicInteger seatsSold = new AtomicInteger();
		CountDownLatch startLine = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(CONTENDERS);

		try (ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS)) {
			for (int i = 0; i < CONTENDERS; i++) {
				
				pool.submit(() -> {
					try {
						startLine.await();
						seatsSold.addAndGet(reservationService.create(eventId, userId, 3).getSeats());
					}
					catch (ApiException ex) {
						// Expected for the losers.
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
					finally {
						finished.countDown();
					}
				});
			}

			startLine.countDown();
			assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(seatsSold.get()).isLessThanOrEqualTo(CAPACITY);
		assertThat(events.findById(eventId).orElseThrow().getReservedSeats()).isEqualTo(seatsSold.get());
		assertThat(reservations.sumActiveSeats(eventId)).isEqualTo(seatsSold.get());
	}

	/**
	 * Cancelling must genuinely return seats to the pool, not just mark a row.
	 */
	@Test
	void cancellationReturnsSeatsAndLetsSomeoneElseIn() {
		List<Long> ids = List.of(
				reservationService.create(eventId, userId, 5).getId(),
				reservationService.create(eventId, userId, 5).getId());

		assertThat(events.findById(eventId).orElseThrow().getReservedSeats()).isEqualTo(CAPACITY);

		reservationService.cancel(ids.get(0), userId, false);

		assertThat(events.findById(eventId).orElseThrow().getReservedSeats()).isEqualTo(5);
		assertThat(reservations.sumActiveSeats(eventId)).isEqualTo(5);

		// The freed seats are really available again.
		assertThat(reservationService.create(eventId, userId, 5)).isNotNull();
		assertThat(events.findById(eventId).orElseThrow().getReservedSeats()).isEqualTo(CAPACITY);
	}

	@Test
	void reservedSeatsNeverExceedsCapacityAfterMixedTraffic() throws Exception {
		CountDownLatch finished = new CountDownLatch(CONTENDERS);

		try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
			for (int i = 0; i < CONTENDERS; i++) {
				int seats = (i % 4) + 1;
				pool.submit(() -> {
					try {
						Long id = reservationService.create(eventId, userId, seats).getId();
						// Half of the winners immediately cancel, churning the counter.
						if (seats % 2 == 0) {
							reservationService.cancel(id, userId, false);
						}
					}
					catch (ApiException ex) {
						// Expected once capacity runs out.
					}
					finally {
						finished.countDown();
					}
				});
			}
			assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
		}

		Event event = events.findById(eventId).orElseThrow();
		assertThat(event.getReservedSeats()).isBetween(0, CAPACITY);
		assertThat(reservations.sumActiveSeats(eventId)).isEqualTo(event.getReservedSeats());
	}
}
