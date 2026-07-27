package com.caglar.secure_ticketing_api.common.config;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
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
class RoleAccessMatrixTest {

	private static final Instant STARTS = Instant.parse("2027-07-01T18:00:00Z");
	private static final Instant ENDS = Instant.parse("2027-07-01T22:00:00Z");

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

	private String adminToken;
	private String organizerToken;
	private String customerToken;
	private String ownerToken;
	private Long eventId;
	private Long draftEventId;
	private Long ownedEventId;
	private Long ownedDraftEventId;
	private Long reservationId;
	private Long ownedReservationId;

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		events.deleteAll();
		users.deleteAll();

		adminToken = tokenFor("admin@test.com", Role.ADMIN);
		organizerToken = tokenFor("organizer@test.com", Role.ORGANIZER);
		customerToken = tokenFor("customer@test.com", Role.CUSTOMER);

		User owner = users.save(new User("owner@test.com", passwordEncoder.encode("secret123"),
				EnumSet.of(Role.ORGANIZER), Instant.now()));
		ownerToken = "Bearer " + jwtService.createAccessToken(owner);
		Long strangerId = users.save(new User("stranger@test.com", passwordEncoder.encode("secret123"),
				EnumSet.of(Role.ORGANIZER), Instant.now())).getId();

		eventId = publishedEvent(strangerId, "Someone else's concert");
		draftEventId = draftEvent(strangerId, "Someone else's draft");
		ownedEventId = publishedEvent(owner.getId(), "Owner's concert");
		ownedDraftEventId = draftEvent(owner.getId(), "Owner's draft");

		reservationId = reservationOn(eventId, strangerId);
		ownedReservationId = reservationOn(eventId, owner.getId());
	}

	private Long publishedEvent(Long ownerId, String title) {
		Event event = new Event(ownerId, title, "Arena", STARTS, ENDS, 50);
		event.publish();
		return events.save(event).getId();
	}

	private Long draftEvent(Long ownerId, String title) {
		return events.save(new Event(ownerId, title, "Studio", STARTS, ENDS, 50)).getId();
	}

	private Long reservationOn(Long event, Long userId) {
		Reservation reservation = reservations.save(
				new Reservation(event, userId, 1, Instant.now(), Instant.now().plusSeconds(900)));
		events.tryReserveSeats(event, 1);
		return reservation.getId();
	}

	@ParameterizedTest(name = "{0} {1} as {2} -> {3}")
	@CsvSource({
			// endpoint                                  role        expected
			"POST,   /api/events,                        ADMIN,      201",
			"POST,   /api/events,                        ORGANIZER,  201",
			"POST,   /api/events,                        CUSTOMER,   403",
			"POST,   /api/events,                        ANONYMOUS,  401",

			"PUT,    /api/events/{event},                ADMIN,      200",
			"PUT,    /api/events/{event},                ORGANIZER,  403",
			"PUT,    /api/events/{event},                CUSTOMER,   403",
			"PUT,    /api/events/{event},                ANONYMOUS,  401",
			"PUT,    /api/events/{ownedEvent},           OWNER,      200",

			"POST,   /api/events/{draftEvent}/publish,   ADMIN,      200",
			"POST,   /api/events/{draftEvent}/publish,   ORGANIZER,  403",
			"POST,   /api/events/{draftEvent}/publish,   CUSTOMER,   403",
			"POST,   /api/events/{draftEvent}/publish,   ANONYMOUS,  401",
			"POST,   /api/events/{ownedDraftEvent}/publish, OWNER,   200",

			"GET,    /api/events,                        ADMIN,      200",
			"GET,    /api/events,                        ORGANIZER,  200",
			"GET,    /api/events,                        CUSTOMER,   200",
			"GET,    /api/events,                        ANONYMOUS,  401",

			"GET,    /api/events/public,                 ADMIN,      200",
			"GET,    /api/events/public,                 ORGANIZER,  200",
			"GET,    /api/events/public,                 CUSTOMER,   200",
			"GET,    /api/events/public,                 ANONYMOUS,  200",

			"POST,   /api/events/{event}/reservations,   ADMIN,      201",
			"POST,   /api/events/{event}/reservations,   ORGANIZER,  201",
			"POST,   /api/events/{event}/reservations,   CUSTOMER,   201",
			"POST,   /api/events/{event}/reservations,   ANONYMOUS,  401",

			"POST,   /api/reservations/{reservation}/confirm, ADMIN,     200",
			"POST,   /api/reservations/{reservation}/confirm, ORGANIZER, 403",
			"POST,   /api/reservations/{reservation}/confirm, CUSTOMER,  403",
			"POST,   /api/reservations/{reservation}/confirm, ANONYMOUS, 401",
			"POST,   /api/reservations/{ownedReservation}/confirm, OWNER, 200",

			"POST,   /api/reservations/{reservation}/cancel,  ADMIN,     200",
			"POST,   /api/reservations/{reservation}/cancel,  ORGANIZER, 403",
			"POST,   /api/reservations/{reservation}/cancel,  CUSTOMER,  403",
			"POST,   /api/reservations/{reservation}/cancel,  ANONYMOUS, 401",
			"POST,   /api/reservations/{ownedReservation}/cancel,  OWNER, 200",

			"POST,   /api/admin/users,                   ADMIN,      201",
			"POST,   /api/admin/users,                   ORGANIZER,  403",
			"POST,   /api/admin/users,                   CUSTOMER,   403",
			"POST,   /api/admin/users,                   OWNER,      403",
			"POST,   /api/admin/users,                   ANONYMOUS,  401",

			"GET,    /actuator/health,                   ANONYMOUS,  200",
			"GET,    /actuator/health,                   CUSTOMER,   200",
			"GET,    /actuator/info,                     ANONYMOUS,  200",
			"GET,    /actuator/metrics,                  ADMIN,      200",
			"GET,    /actuator/metrics,                  ORGANIZER,  403",
			"GET,    /actuator/metrics,                  CUSTOMER,   403",
			"GET,    /actuator/metrics,                  ANONYMOUS,  401",

			// the schema lists every route and its shape, so it is not public either
			"GET,    /v3/api-docs,                       CUSTOMER,   200",
			"GET,    /v3/api-docs,                       ANONYMOUS,  401",
			"GET,    /swagger-ui/index.html,             ANONYMOUS,  401" })
	void theAccessMatrixHolds(String method, String path, String role, int expected) throws Exception {
		mockMvc.perform(request(method, resolve(path), role))
				.andExpect(status().is(expected));
	}

	private MockHttpServletRequestBuilder request(String method, String path, String role) {
		MockHttpServletRequestBuilder builder = MockMvcRequestBuilders
				.request(HttpMethod.valueOf(method), path)
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", UUID.randomUUID().toString());

		String token = tokenOf(role);
		if (token != null) {
			builder.header(HttpHeaders.AUTHORIZATION, token);
		}
		if (!"GET".equals(method)) {
			builder.content(bodyFor(path));
		}
		return builder;
	}

	private String bodyFor(String path) {
		if (path.endsWith("/reservations")) {
			return "{\"seats\":1}";
		}
		if (path.startsWith("/api/admin/users")) {
			return """
					{"email":"matrix-%s@test.com","password":"secret123","roles":["CUSTOMER"]}
					""".formatted(java.util.UUID.randomUUID());
		}
		if (path.startsWith("/api/events") && !path.contains("publish")) {
			return """
					{"title":"Title","description":"d","venue":"Venue",
					 "startsAt":"2027-07-01T18:00:00Z","endsAt":"2027-07-01T22:00:00Z","capacity":50}
					""";
		}
		return "{}";
	}

	private String resolve(String path) {
		return path.replace("{ownedDraftEvent}", String.valueOf(ownedDraftEventId))
				.replace("{ownedEvent}", String.valueOf(ownedEventId))
				.replace("{draftEvent}", String.valueOf(draftEventId))
				.replace("{event}", String.valueOf(eventId))
				.replace("{ownedReservation}", String.valueOf(ownedReservationId))
				.replace("{reservation}", String.valueOf(reservationId));
	}

	private String tokenOf(String role) {
		return switch (role) {
			case "ADMIN" -> adminToken;
			case "ORGANIZER" -> organizerToken;
			case "CUSTOMER" -> customerToken;
			case "OWNER" -> ownerToken;
			default -> null;
		};
	}

	private String tokenFor(String email, Role role) {
		User user = users.save(new User(email, passwordEncoder.encode("secret123"),
				EnumSet.of(role), Instant.now()));
		return "Bearer " + jwtService.createAccessToken(user);
	}
}
