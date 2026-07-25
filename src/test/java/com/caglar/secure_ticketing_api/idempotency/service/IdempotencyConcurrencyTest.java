package com.caglar.secure_ticketing_api.idempotency.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.auth.service.JwtService;
import com.caglar.secure_ticketing_api.event.domain.Event;
import com.caglar.secure_ticketing_api.event.domain.EventRepository;
import com.caglar.secure_ticketing_api.idempotency.domain.IdempotencyRecordRepository;
import com.caglar.secure_ticketing_api.reservation.domain.ReservationRepository;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IdempotencyConcurrencyTest {

	private static final int CONTENDERS = 20;
	private static final int CAPACITY = 50;
	private static final int SEATS = 2;

	@LocalServerPort
	private int port;

	@Autowired
	private UserRepository users;

	@Autowired
	private EventRepository events;

	@Autowired
	private ReservationRepository reservations;

	@Autowired
	private IdempotencyRecordRepository idempotencyRecords;

	@Autowired
	private JwtService jwtService;

	private final HttpClient http = HttpClient.newHttpClient();

	private String token;
	private Long eventId;

	@BeforeEach
	void setUp() {
		idempotencyRecords.deleteAll();
		reservations.deleteAll();
		events.deleteAll();
		users.deleteAll();

		User customer = users.save(new User("racer@test.com", "hash",
				EnumSet.of(Role.CUSTOMER), Instant.now()));
		token = "Bearer " + jwtService.createAccessToken(customer);

		Event event = new Event(customer.getId(), "Hot Ticket", "Arena",
				Instant.parse("2027-07-01T18:00:00Z"), Instant.parse("2027-07-01T22:00:00Z"), CAPACITY);
		event.publish();
		eventId = events.save(event).getId();
	}

	@Test
	void twentySimultaneousRetriesOfOneRequestProduceOneReservation() throws Exception {
		AtomicInteger created = new AtomicInteger();
		AtomicInteger replayed = new AtomicInteger();
		AtomicInteger inProgress = new AtomicInteger();
		AtomicInteger unexpected = new AtomicInteger();

		race(index -> "the-one-key", response -> {
			boolean isReplay = response.headers().firstValue("X-Idempotent-Replay").isPresent();
			switch (response.statusCode()) {
				case 201 -> (isReplay ? replayed : created).incrementAndGet();
				case 409 -> inProgress.incrementAndGet();
				default -> unexpected.incrementAndGet();
			}
		});

		assertThat(reservations.count()).isEqualTo(1);
		assertThat(events.findById(eventId).orElseThrow().getReservedSeats()).isEqualTo(SEATS);
		assertThat(idempotencyRecords.count()).isEqualTo(1);

		assertThat(created.get()).as("exactly one request may do the work").isEqualTo(1);
		assertThat(unexpected.get()).as("no unexpected status codes").isZero();
		assertThat(created.get() + replayed.get() + inProgress.get()).isEqualTo(CONTENDERS);
	}

	@Test
	void distinctKeysAreNotBlockedByEachOther() throws Exception {
		AtomicInteger created = new AtomicInteger();

		race(index -> "key-" + index, response -> {
			if (response.statusCode() == 201) {
				created.incrementAndGet();
			}
		});

		assertThat(created.get()).isEqualTo(CONTENDERS);
		assertThat(reservations.count()).isEqualTo(CONTENDERS);
		assertThat(events.findById(eventId).orElseThrow().getReservedSeats())
				.isEqualTo(CONTENDERS * SEATS);
	}

	private void race(java.util.function.IntFunction<String> keyFor,
			java.util.function.Consumer<HttpResponse<String>> onReply) throws InterruptedException {

		CountDownLatch startLine = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(CONTENDERS);

		try (ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS)) {
			for (int i = 0; i < CONTENDERS; i++) {
				int index = i;
				pool.submit(() -> {
					try {
						startLine.await();
						onReply.accept(reserve(keyFor.apply(index)));
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
					catch (IOException ex) {
						throw new IllegalStateException(ex);
					}
					finally {
						finished.countDown();
					}
				});
			}

			startLine.countDown();
			assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
		}
	}

	private HttpResponse<String> reserve(String key) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:%d/api/events/%d/reservations".formatted(port, eventId)))
				.header("Content-Type", "application/json")
				.header("Authorization", token)
				.header("Idempotency-Key", key)
				.POST(HttpRequest.BodyPublishers.ofString("{\"seats\":%d}".formatted(SEATS)))
				.build();

		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
