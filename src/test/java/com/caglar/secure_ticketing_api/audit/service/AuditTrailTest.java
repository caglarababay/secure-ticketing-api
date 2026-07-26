package com.caglar.secure_ticketing_api.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
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

import com.caglar.secure_ticketing_api.audit.AuditLogTestSupport;
import com.caglar.secure_ticketing_api.audit.domain.AuditAction;
import com.caglar.secure_ticketing_api.audit.domain.AuditLog;
import com.caglar.secure_ticketing_api.audit.domain.AuditLogRepository;
import com.caglar.secure_ticketing_api.audit.domain.AuditResource;
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
class AuditTrailTest {

	private static final int CAPACITY = 5;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AuditLogRepository auditLogs;

	@Autowired
	private AuditLogTestSupport auditTrail;

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

	private User organizer;
	private String organizerToken;
	private String customerToken;
	private Long customerId;
	private Long publishedEventId;
	private Long draftEventId;

	@BeforeEach
	void setUp() {
		auditTrail.clear();
		idempotencyRecords.deleteAll();
		reservations.deleteAll();
		events.deleteAll();
		users.deleteAll();

		organizer = createUser("organizer@test.com", Role.ORGANIZER);
		organizerToken = "Bearer " + jwtService.createAccessToken(organizer);

		User customer = createUser("customer@test.com", Role.CUSTOMER);
		customerId = customer.getId();
		customerToken = "Bearer " + jwtService.createAccessToken(customer);

		Event published = new Event(organizer.getId(), "Concert", "Arena",
				Instant.parse("2027-07-01T18:00:00Z"), Instant.parse("2027-07-01T22:00:00Z"), CAPACITY);
		published.publish();
		publishedEventId = events.save(published).getId();

		draftEventId = events.save(new Event(organizer.getId(), "Draft", "Studio",
				Instant.parse("2027-08-01T18:00:00Z"), Instant.parse("2027-08-01T22:00:00Z"), CAPACITY))
				.getId();
	}

	// --- what gets recorded ------------------------------------------------------

	@Test
	void publishingAnEventIsRecorded() throws Exception {
		mockMvc.perform(post("/api/events/" + draftEventId + "/publish")
						.header(HttpHeaders.AUTHORIZATION, organizerToken))
				.andExpect(status().isOk());

		AuditLog entry = onlyEntry();
		assertThat(entry.getAction()).isEqualTo(AuditAction.EVENT_PUBLISHED);
		assertThat(entry.getResourceType()).isEqualTo(AuditResource.EVENT);
		assertThat(entry.getResourceId()).isEqualTo(draftEventId);
		assertThat(entry.getActorId()).isEqualTo(organizer.getId());
	}

	@Test
	void reservingIsRecordedAgainstTheCustomer() throws Exception {
		reserve(2).andExpect(status().isCreated());

		AuditLog entry = onlyEntry();
		assertThat(entry.getAction()).isEqualTo(AuditAction.RESERVATION_CREATED);
		assertThat(entry.getResourceType()).isEqualTo(AuditResource.RESERVATION);
		assertThat(entry.getResourceId()).isEqualTo(reservations.findAll().getFirst().getId());
		assertThat(entry.getActorId()).isEqualTo(customerId);
	}

	@Test
	void cancellingIsRecordedSeparatelyFromCreating() throws Exception {
		reserve(2).andExpect(status().isCreated());
		Long reservationId = reservations.findAll().getFirst().getId();

		mockMvc.perform(post("/api/reservations/" + reservationId + "/cancel")
						.header(HttpHeaders.AUTHORIZATION, customerToken))
				.andExpect(status().isOk());

		assertThat(auditLogs.findAll())
				.extracting(AuditLog::getAction)
				.containsExactlyInAnyOrder(
						AuditAction.RESERVATION_CREATED, AuditAction.RESERVATION_CANCELLED);
	}

	@Test
	void theCallersAddressAndClientAreRecorded() throws Exception {
		mockMvc.perform(post("/api/events/" + draftEventId + "/publish")
						.header(HttpHeaders.AUTHORIZATION, organizerToken)
						.header(HttpHeaders.USER_AGENT, "curl/8.4.0")
						.with(request -> {
							request.setRemoteAddr("203.0.113.7");
							return request;
						}))
				.andExpect(status().isOk());

		AuditLog entry = onlyEntry();
		assertThat(entry.getIp()).isEqualTo("203.0.113.7");
		assertThat(entry.getUserAgent()).isEqualTo("curl/8.4.0");
		assertThat(entry.getCreatedAt()).isNotNull();
	}

	@Test
	void registeringIsRecordedAgainstTheNewAccount() throws Exception {
		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"newcomer@test.com\",\"password\":\"secret123\"}"))
				.andExpect(status().isCreated());

		Long newUserId = users.findByEmail("newcomer@test.com").orElseThrow().getId();
		AuditLog entry = onlyEntry();
		assertThat(entry.getAction()).isEqualTo(AuditAction.REGISTERED);
		assertThat(entry.getActorId())
				.as("the actor is the account being created — there is nobody else")
				.isEqualTo(newUserId);
		assertThat(entry.getResourceId()).isEqualTo(newUserId);
	}

	@Test
	void confirmingIsRecordedSeparatelyFromCreating() throws Exception {
		reserve(2).andExpect(status().isCreated());
		Long reservationId = reservations.findAll().getFirst().getId();

		mockMvc.perform(post("/api/reservations/" + reservationId + "/confirm")
						.header(HttpHeaders.AUTHORIZATION, customerToken))
				.andExpect(status().isOk());

		assertThat(auditLogs.findAll())
				.extracting(AuditLog::getAction)
				.containsExactlyInAnyOrder(
						AuditAction.RESERVATION_CREATED, AuditAction.RESERVATION_CONFIRMED);
	}

	/** A client that sends no User-Agent is still worth a record. */
	@Test
	void aRequestWithoutAUserAgentIsStillRecorded() throws Exception {
		mockMvc.perform(post("/api/events/" + draftEventId + "/publish")
						.header(HttpHeaders.AUTHORIZATION, organizerToken))
				.andExpect(status().isOk());

		AuditLog entry = onlyEntry();
		assertThat(entry.getUserAgent()).isNull();
		assertThat(entry.getActorId()).isEqualTo(organizer.getId());
	}

	@Test
	void nothingSensitiveEndsUpInTheTrail() throws Exception {
		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"secretive@test.com\",\"password\":\"hunter2-very-secret\"}"))
				.andExpect(status().isCreated());
		reserve(1).andExpect(status().isCreated());

		assertThat(auditLogs.findAll()).allSatisfy(entry -> {
			String text = "%s|%s|%s".formatted(entry.getIp(), entry.getUserAgent(), entry.getAction());
			assertThat(text)
					.as("no raw password")
					.doesNotContain("hunter2-very-secret")
					.doesNotContain("secret123")
					.as("no BCrypt hash")
					.doesNotContain("$2a$")
					.as("no JWT")
					.doesNotContain("eyJ")
					.as("no request body")
					.doesNotContain("seats");
		});
	}

	// --- atomicity --------------------

	@Test
	void aRejectedActionLeavesNoTrace() throws Exception {
		reserve(CAPACITY + 1)
				.andExpect(status().isConflict());

		assertThat(reservations.count()).isZero();
		assertThat(auditLogs.count()).isZero();
	}

	@Test
	void anUnauthorisedActionLeavesNoTrace() throws Exception {
		mockMvc.perform(post("/api/events/" + draftEventId + "/publish")
						.header(HttpHeaders.AUTHORIZATION, customerToken))
				.andExpect(status().isForbidden());

		assertThat(auditLogs.count()).isZero();
	}

	@Test
	void aRecordExistsForEveryActionAndNoMore() throws Exception {
		reserve(1).andExpect(status().isCreated());
		reserve(CAPACITY + 1).andExpect(status().isConflict());
		reserve(1).andExpect(status().isCreated());

		assertThat(auditLogs.count())
				.as("two reservations happened, one attempt did not")
				.isEqualTo(2);
	}

	// --- helpers --------------

	private AuditLog onlyEntry() {
		List<AuditLog> all = auditLogs.findAll();
		assertThat(all).hasSize(1);
		return all.getFirst();
	}

	private ResultActions reserve(int seats) throws Exception {
		return mockMvc.perform(post("/api/events/" + publishedEventId + "/reservations")
				.header(HttpHeaders.AUTHORIZATION, customerToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"seats\":%d}".formatted(seats)));
	}

	private User createUser(String email, Role role) {
		return users.save(new User(email, passwordEncoder.encode("secret123"),
				EnumSet.of(role), Instant.now()));
	}
}
