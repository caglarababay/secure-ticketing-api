package com.caglar.secure_ticketing_api.reservation.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.transaction.annotation.Transactional;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.auth.service.JwtService;
import com.caglar.secure_ticketing_api.event.domain.Event;
import com.caglar.secure_ticketing_api.event.domain.EventRepository;
import com.caglar.secure_ticketing_api.reservation.domain.Reservation;
import com.caglar.secure_ticketing_api.reservation.domain.ReservationRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReservationApiTest {

	private static final Instant STARTS = Instant.parse("2027-07-01T18:00:00Z");
	private static final Instant ENDS = STARTS.plus(Duration.ofHours(4));
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
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	private User customer;
	private String customerToken;
	private String otherToken;
	private String adminToken;
	private Long publishedEventId;
	private Long draftEventId;

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		events.deleteAll();
		users.deleteAll();

		customer = createUser("customer@test.com", Role.CUSTOMER);
		customerToken = bearer(customer);
		otherToken = bearer(createUser("other@test.com", Role.CUSTOMER));
		adminToken = bearer(createUser("admin@test.com", Role.ADMIN));

		Event published = new Event(customer.getId(), "Concert", "Arena", STARTS, ENDS, 10);
		published.publish();
		publishedEventId = events.save(published).getId();

		draftEventId = events.save(new Event(customer.getId(), "Draft", "Studio", STARTS, ENDS, 10)).getId();
	}

	private User createUser(String email, Role role) {
		return users.save(new User(email, passwordEncoder.encode("secret123"),
				EnumSet.of(role), Instant.now()));
	}

	private String bearer(User user) {
		return "Bearer " + jwtService.createAccessToken(user);
	}

	private Long reserve(int seats) {
		Reservation reservation = reservations.save(
				new Reservation(publishedEventId, customer.getId(), seats, Instant.now(), Instant.now().plusSeconds(900)));
		events.tryReserveSeats(publishedEventId, seats);
		return reservation.getId();
	}

	private static String seatsJson(int seats) {
		return "{\"seats\":%d}".formatted(seats);
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

	// --- create -------------------------------------------------------------

	@Test
	void reservingAPublishedEventCreatesAPendingReservation() throws Exception {
		mockMvc.perform(post("/api/events/" + publishedEventId + "/reservations")
						.header(HttpHeaders.AUTHORIZATION, customerToken)
						.header(IDEMPOTENCY_KEY, newKey())
						.contentType(MediaType.APPLICATION_JSON).content(seatsJson(2)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.seats").value(2))
				.andExpect(jsonPath("$.eventId").value(publishedEventId))
				.andExpect(jsonPath("$.userId").value(customer.getId()));
	}

	@Test
	void reservingADraftEventIsRejected() throws Exception {
		mockMvc.perform(post("/api/events/" + draftEventId + "/reservations")
						.header(HttpHeaders.AUTHORIZATION, customerToken)
						.header(IDEMPOTENCY_KEY, newKey())
						.contentType(MediaType.APPLICATION_JSON).content(seatsJson(1)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EVENT_NOT_PUBLISHED"));
	}

	@Test
	void askingForMoreSeatsThanRemainIsRejected() throws Exception {
		mockMvc.perform(post("/api/events/" + publishedEventId + "/reservations")
						.header(HttpHeaders.AUTHORIZATION, customerToken)
						.header(IDEMPOTENCY_KEY, newKey())
						.contentType(MediaType.APPLICATION_JSON).content(seatsJson(11)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_CAPACITY"));
	}

	@Test
	void missingEventIsNotFound() throws Exception {
		mockMvc.perform(post("/api/events/999999/reservations")
						.header(HttpHeaders.AUTHORIZATION, customerToken)
						.header(IDEMPOTENCY_KEY, newKey())
						.contentType(MediaType.APPLICATION_JSON).content(seatsJson(1)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void seatsMustBePositive() throws Exception {
		mockMvc.perform(post("/api/events/" + publishedEventId + "/reservations")
						.header(HttpHeaders.AUTHORIZATION, customerToken)
						.header(IDEMPOTENCY_KEY, newKey())
						.contentType(MediaType.APPLICATION_JSON).content(seatsJson(0)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[?(@.field == 'seats')]").exists());
	}

	@Test
	void reservingRequiresAuthentication() throws Exception {
		mockMvc.perform(post("/api/events/" + publishedEventId + "/reservations")
						.contentType(MediaType.APPLICATION_JSON).content(seatsJson(1)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	// --- state transitions --------------------------------------------------

	@Test
	void pendingCanBeConfirmedThenNotAgain() throws Exception {
		Long id = reserve(2);

		mockMvc.perform(post("/api/reservations/" + id + "/confirm")
						.header(HttpHeaders.AUTHORIZATION, customerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		mockMvc.perform(post("/api/reservations/" + id + "/confirm")
						.header(HttpHeaders.AUTHORIZATION, customerToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
	}

	@Test
	void cancelledCannotBeConfirmed() throws Exception {
		Long id = reserve(2);

		mockMvc.perform(post("/api/reservations/" + id + "/cancel")
						.header(HttpHeaders.AUTHORIZATION, customerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));

		mockMvc.perform(post("/api/reservations/" + id + "/confirm")
						.header(HttpHeaders.AUTHORIZATION, customerToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
	}

	@Test
	void confirmedCanStillBeCancelled() throws Exception {
		Long id = reserve(2);
		mockMvc.perform(post("/api/reservations/" + id + "/confirm")
				.header(HttpHeaders.AUTHORIZATION, customerToken)).andExpect(status().isOk());

		mockMvc.perform(post("/api/reservations/" + id + "/cancel")
						.header(HttpHeaders.AUTHORIZATION, customerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	@Test
	void cancellingTwiceIsRejected() throws Exception {
		Long id = reserve(2);
		mockMvc.perform(post("/api/reservations/" + id + "/cancel")
				.header(HttpHeaders.AUTHORIZATION, customerToken)).andExpect(status().isOk());

		mockMvc.perform(post("/api/reservations/" + id + "/cancel")
						.header(HttpHeaders.AUTHORIZATION, customerToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
	}

	// --- ownership ----------------------------------------------------------

	@Test
	void someoneElsesReservationCannotBeConfirmed() throws Exception {
		Long id = reserve(2);

		mockMvc.perform(post("/api/reservations/" + id + "/confirm")
						.header(HttpHeaders.AUTHORIZATION, otherToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void someoneElsesReservationCannotBeCancelled() throws Exception {
		Long id = reserve(2);

		mockMvc.perform(post("/api/reservations/" + id + "/cancel")
						.header(HttpHeaders.AUTHORIZATION, otherToken))
				.andExpect(status().isForbidden());
	}

	@Test
	void adminCanConfirmAnyReservation() throws Exception {
		Long id = reserve(2);

		mockMvc.perform(post("/api/reservations/" + id + "/confirm")
						.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	@Test
	void missingReservationIsNotFound() throws Exception {
		mockMvc.perform(post("/api/reservations/999999/confirm")
						.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}
}
