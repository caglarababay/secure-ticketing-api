package com.caglar.secure_ticketing_api.idempotency.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.auth.service.JwtService;
import com.caglar.secure_ticketing_api.event.domain.Event;
import com.caglar.secure_ticketing_api.event.domain.EventRepository;
import com.caglar.secure_ticketing_api.idempotency.domain.IdempotencyRecordRepository;
import com.caglar.secure_ticketing_api.reservation.domain.ReservationRepository;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdempotencyReplaySemanticsTest {

	private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private EventRepository events;

	@Autowired
	private ReservationRepository reservations;

	@Autowired
	private IdempotencyRecordRepository idempotencyRecords;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	private String token;
	private Long eventId;

	@BeforeEach
	void setUp() {
		idempotencyRecords.deleteAll();
		reservations.deleteAll();
		events.deleteAll();
		users.deleteAll();

		User customer = users.save(new User("replay@test.com", passwordEncoder.encode("secret123"),
				EnumSet.of(Role.CUSTOMER), Instant.now()));
		token = "Bearer " + jwtService.createAccessToken(customer);

		Event event = new Event(customer.getId(), "Concert", "Arena",
				Instant.parse("2027-07-01T18:00:00Z"), Instant.parse("2027-07-01T22:00:00Z"), 10);
		event.publish();
		eventId = events.save(event).getId();
	}

	@Test
	void aReplayReflectsWhatHappenedToTheResourceSince() throws Exception {
		String key = UUID.randomUUID().toString();

		reserve(key)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"));

		Long reservationId = reservations.findAll().getFirst().getId();
		mockMvc.perform(post("/api/reservations/" + reservationId + "/confirm")
						.header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk());

		reserve(key)
				.andExpect(status().isCreated())
				.andExpect(header().string("X-Idempotent-Replay", "true"))
				.andExpect(jsonPath("$.id").value(reservationId))
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		assertThat(reservations.count()).isEqualTo(1);
	}

	@Test
	void aReplayAfterCancellationShowsTheCancelledReservation() throws Exception {
		String key = UUID.randomUUID().toString();

		reserve(key).andExpect(status().isCreated());
		Long reservationId = reservations.findAll().getFirst().getId();

		mockMvc.perform(post("/api/reservations/" + reservationId + "/cancel")
						.header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk());

		reserve(key)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("CANCELLED"));

		assertThat(events.findById(eventId).orElseThrow().getReservedSeats()).isZero();
		assertThat(reservations.count()).isEqualTo(1);
	}

	private org.springframework.test.web.servlet.ResultActions reserve(String key) throws Exception {
		return mockMvc.perform(post("/api/events/" + eventId + "/reservations")
				.header(HttpHeaders.AUTHORIZATION, token)
				.header(IDEMPOTENCY_KEY, key)
				.contentType(MediaType.APPLICATION_JSON).content("{\"seats\":2}"));
	}
}
