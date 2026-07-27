package com.caglar.secure_ticketing_api.idempotency.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.auth.service.JwtService;
import com.caglar.secure_ticketing_api.event.domain.Event;
import com.caglar.secure_ticketing_api.event.domain.EventRepository;
import com.caglar.secure_ticketing_api.idempotency.domain.IdempotencyRecordRepository;
import com.caglar.secure_ticketing_api.reservation.domain.ReservationRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import tools.jackson.databind.ObjectMapper;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdempotencyApiTest {

	private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
	private static final String REPLAY_HEADER = "X-Idempotent-Replay";
	private static final int CAPACITY = 10;

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

	@Autowired
	private ObjectMapper objectMapper;

	@PersistenceContext
	private EntityManager entityManager;

	private TransactionTemplate transactionTemplate;

	private String customerToken;
	private String otherToken;
	private Long eventId;

	@Autowired
	void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void setUp() {
		idempotencyRecords.deleteAll();
		reservations.deleteAll();
		events.deleteAll();
		users.deleteAll();

		User customer = createUser("customer@test.com");
		customerToken = "Bearer " + jwtService.createAccessToken(customer);
		otherToken = "Bearer " + jwtService.createAccessToken(createUser("other@test.com"));

		Event event = new Event(customer.getId(), "Concert", "Arena",
				Instant.parse("2027-07-01T18:00:00Z"), Instant.parse("2027-07-01T22:00:00Z"), CAPACITY);
		event.publish();
		eventId = events.save(event).getId();
	}

	// --- replay ---------------------------------------------------------------

	@Test
	void repeatingARequestReturnsTheFirstReservationInsteadOfMakingAnother() throws Exception {
		String key = newKey();

		String firstId = reserve(customerToken, key, 2)
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist(REPLAY_HEADER))
				.andReturn().getResponse().getContentAsString();

		reserve(customerToken, key, 2)
				.andExpect(status().isCreated())
				.andExpect(header().string(REPLAY_HEADER, "true"))
				.andExpect(jsonPath("$.id").value(idOf(firstId)));

		assertThat(reservations.count()).isEqualTo(1);
	}

	@Test
	void aReplayDoesNotConsumeCapacityTwice() throws Exception {
		String key = newKey();

		reserve(customerToken, key, 3).andExpect(status().isCreated());
		reserve(customerToken, key, 3).andExpect(status().isCreated());

		assertThat(events.findById(eventId).orElseThrow().getReservedSeats()).isEqualTo(3);
	}

	// --- misuse ----------------------------------------------------------------

	@Test
	void reusingAKeyWithADifferentPayloadIsRejected() throws Exception {
		String key = newKey();

		reserve(customerToken, key, 2).andExpect(status().isCreated());

		reserve(customerToken, key, 5)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		assertThat(reservations.count()).isEqualTo(1);
	}

	@Test
	void theSameKeyOnADifferentEventIsAMismatch() throws Exception {
		Event other = new Event(users.findAll().getFirst().getId(), "Other", "Hall",
				Instant.parse("2027-08-01T18:00:00Z"), Instant.parse("2027-08-01T22:00:00Z"), CAPACITY);
		other.publish();
		Long otherEventId = events.save(other).getId();
		String key = newKey();

		reserve(customerToken, key, 2).andExpect(status().isCreated());

		mockMvc.perform(post("/api/events/" + otherEventId + "/reservations")
						.header(HttpHeaders.AUTHORIZATION, customerToken)
						.header(IDEMPOTENCY_KEY, key)
						.contentType(MediaType.APPLICATION_JSON).content(seatsJson(2)))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void aMissingKeyIsRejected() throws Exception {
		mockMvc.perform(post("/api/events/" + eventId + "/reservations")
						.header(HttpHeaders.AUTHORIZATION, customerToken)
						.contentType(MediaType.APPLICATION_JSON).content(seatsJson(1)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

		assertThat(reservations.count()).isZero();
	}

	@Test
	void aBlankKeyIsRejected() throws Exception {
		reserve(customerToken, "   ", 1)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));
	}

	@Test
	void anOverlongKeyIsRejected() throws Exception {
		reserve(customerToken, "k".repeat(101), 1)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));

		assertThat(reservations.count()).isZero();
	}

	// --- scope ------------------------------------------------------------------

	@Test
	void twoUsersMayUseTheSameKeyIndependently() throws Exception {
		String key = newKey();

		reserve(customerToken, key, 1)
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist(REPLAY_HEADER));

		reserve(otherToken, key, 1)
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist(REPLAY_HEADER));

		assertThat(reservations.count()).isEqualTo(2);
	}

	// --- discard ------------------------------------------------------------------

	@Test
	void aKeyIsFreeAgainAfterTheRequestWasRejected() throws Exception {
		String key = newKey();

		reserve(customerToken, key, CAPACITY + 1)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_CAPACITY"));

		assertThat(idempotencyRecords.count()).isZero();

		reserve(customerToken, key, 2).andExpect(status().isCreated());
	}

	@Test
	void aKeyIsFreeAgainAfterAValidationFailure() throws Exception {
		String key = newKey();

		reserve(customerToken, key, 0)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		reserve(customerToken, key, 2).andExpect(status().isCreated());
	}

	// --- retention -------------------------------------------------------------

	@Test
	void anExpiredKeyCanBeClaimedAgain() throws Exception {
		String key = newKey();

		reserve(customerToken, key, 1).andExpect(status().isCreated());
		expire(key);

		reserve(customerToken, key, 1)
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist(REPLAY_HEADER));

		assertThat(reservations.count()).isEqualTo(2);
	}

	// --- helpers ------------------------------------------------------------------

	private ResultActions reserve(String token, String key, int seats) throws Exception {
		return mockMvc.perform(post("/api/events/" + eventId + "/reservations")
				.header(HttpHeaders.AUTHORIZATION, token)
				.header(IDEMPOTENCY_KEY, key)
				.contentType(MediaType.APPLICATION_JSON).content(seatsJson(seats)));
	}

	private void expire(String key) {
		transactionTemplate.executeWithoutResult(status -> entityManager
				.createQuery("update IdempotencyRecord r set r.expiresAt = :past where r.key = :key")
				.setParameter("past", Instant.now().minus(Duration.ofMinutes(1)))
				.setParameter("key", key)
				.executeUpdate());
	}

	private User createUser(String email) {
		return users.save(new User(email, passwordEncoder.encode("secret123"),
				EnumSet.of(Role.CUSTOMER), Instant.now()));
	}

	private static String seatsJson(int seats) {
		return "{\"seats\":%d}".formatted(seats);
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

	private long idOf(String json) {
		return objectMapper.readTree(json).get("id").asLong();
	}
}
