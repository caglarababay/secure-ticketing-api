package com.caglar.secure_ticketing_api.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.caglar.secure_ticketing_api.audit.AuditLogTestSupport;
import com.caglar.secure_ticketing_api.audit.domain.AuditLogRepository;
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
@TestPropertySource(properties = {
		"ratelimit.reservation.capacity=2",
		"ratelimit.reservation.window=1m" })
class RateLimitOrderingTest {

	private static final int CAPACITY = 50;

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
	private AuditLogRepository auditLogs;

	@Autowired
	private AuditLogTestSupport auditTrail;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	private String token;
	private Long eventId;

	@BeforeEach
	void setUp() {
		auditTrail.clear();
		idempotencyRecords.deleteAll();
		reservations.deleteAll();
		events.deleteAll();
		users.deleteAll();

		User customer = users.save(new User("throttled-%s@test.com".formatted(UUID.randomUUID()),
				passwordEncoder.encode("secret123"), EnumSet.of(Role.CUSTOMER), Instant.now()));
		token = "Bearer " + jwtService.createAccessToken(customer);

		Event event = new Event(customer.getId(), "Concert", "Arena",
				Instant.parse("2027-07-01T18:00:00Z"), Instant.parse("2027-07-01T22:00:00Z"), CAPACITY);
		event.publish();
		eventId = events.save(event).getId();
	}

	@Test
	void aThrottledRequestReachesNoneOfTheBusinessLayers() throws Exception {
		reserve(newKey()).andExpect(status().isCreated());
		reserve(newKey()).andExpect(status().isCreated());
		long reservationsBefore = reservations.count();
		long keysBefore = idempotencyRecords.count();
		long auditBefore = auditLogs.count();

		reserve(newKey())
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

		assertThat(reservations.count()).as("no reservation").isEqualTo(reservationsBefore);
		assertThat(idempotencyRecords.count()).as("no idempotency record").isEqualTo(keysBefore);
		assertThat(auditLogs.count()).as("no audit entry").isEqualTo(auditBefore);
	}

	@Test
	void aThrottledRequestDoesNotConsumeCapacity() throws Exception {
		reserve(newKey()).andExpect(status().isCreated());
		reserve(newKey()).andExpect(status().isCreated());
		int seatsBefore = events.findById(eventId).orElseThrow().getReservedSeats();

		reserve(newKey()).andExpect(status().isTooManyRequests());

		assertThat(events.findById(eventId).orElseThrow().getReservedSeats()).isEqualTo(seatsBefore);
	}

	@Test
	void aKeyOfferedToAThrottledRequestIsStillUnused() throws Exception {
		String key = newKey();
		reserve(newKey()).andExpect(status().isCreated());
		reserve(newKey()).andExpect(status().isCreated());

		reserve(key).andExpect(status().isTooManyRequests());

		assertThat(idempotencyRecords.findAll())
				.as("the throttled request left no claim on the key")
				.noneMatch(record -> record.getKey().equals(key));

		String otherToken = tokenForNewCustomer();
		mockMvc.perform(post("/api/events/" + eventId + "/reservations")
						.header(HttpHeaders.AUTHORIZATION, otherToken)
						.header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON).content("{\"seats\":1}"))
				.andExpect(status().isCreated());
	}

	@Test
	void allowedRequestsStillGoAllTheWayThrough() throws Exception {
		reserve(newKey()).andExpect(status().isCreated());

		assertThat(reservations.count()).isEqualTo(1);
		assertThat(idempotencyRecords.count()).isEqualTo(1);
		assertThat(auditLogs.count()).isEqualTo(1);
	}

	private ResultActions reserve(String key) throws Exception {
		return mockMvc.perform(post("/api/events/" + eventId + "/reservations")
				.header(HttpHeaders.AUTHORIZATION, token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("{\"seats\":1}"));
	}

	private String tokenForNewCustomer() {
		User other = users.save(new User("other-%s@test.com".formatted(UUID.randomUUID()),
				passwordEncoder.encode("secret123"), EnumSet.of(Role.CUSTOMER), Instant.now()));
		return "Bearer " + jwtService.createAccessToken(other);
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}
}
