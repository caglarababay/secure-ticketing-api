package com.caglar.secure_ticketing_api.event.api;

import static com.caglar.secure_ticketing_api.event.api.EventTestSupport.eventJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

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


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EventOwnershipTest {

	private static final Instant STARTS = Instant.parse("2027-07-01T18:00:00Z");
	private static final Instant ENDS = STARTS.plus(Duration.ofHours(4));

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private EventRepository events;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	private EventTestSupport support;
	private User owner;
	private String ownerToken;
	private String otherOrganizerToken;
	private String adminToken;
	private String customerToken;

	@BeforeEach
	void setUp() {
		events.deleteAll();
		users.deleteAll();
		support = new EventTestSupport(users, passwordEncoder, jwtService);

		owner = support.createUser("owner@test.com", Role.ORGANIZER);
		ownerToken = support.bearerFor(owner);
		otherOrganizerToken = support.bearerFor(support.createUser("other@test.com", Role.ORGANIZER));
		adminToken = support.bearerFor(support.createUser("admin@test.com", Role.ADMIN));
		customerToken = support.bearerFor(support.createUser("customer@test.com", Role.CUSTOMER));
	}

	private Long ownedEventId() {
		return events.save(new Event(owner.getId(), "Concert", "Arena", STARTS, ENDS, 500)).getId();
	}

	private static String updateJson() {
		return eventJson("Renamed", STARTS, ENDS, 600);
	}

	@Test
	void organizerCanCreateADraft() throws Exception {
		mockMvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(eventJson("Concert", STARTS, ENDS, 500)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.published").value(false))
				.andExpect(jsonPath("$.ownerId").value(owner.getId()))
				.andExpect(jsonPath("$.version").value(0));
	}

	@Test
	void customerCannotCreateAnEvent() throws Exception {
		mockMvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, customerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(eventJson("Concert", STARTS, ENDS, 500)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void anotherOrganizerCannotUpdateSomeoneElsesEvent() throws Exception {
		Long id = ownedEventId();

		mockMvc.perform(put("/api/events/" + id).header(HttpHeaders.AUTHORIZATION, otherOrganizerToken)
						.contentType(MediaType.APPLICATION_JSON).content(updateJson()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"))
				.andExpect(jsonPath("$.message").value("You do not own this event."))
				.andExpect(jsonPath("$.path").value("/api/events/" + id));
	}

	@Test
	void ownerCanUpdateTheirOwnEvent() throws Exception {
		Long id = ownedEventId();

		mockMvc.perform(put("/api/events/" + id).header(HttpHeaders.AUTHORIZATION, ownerToken)
						.contentType(MediaType.APPLICATION_JSON).content(updateJson()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Renamed"))
				.andExpect(jsonPath("$.capacity").value(600));
	}

	@Test
	void adminCanUpdateAnyEvent() throws Exception {
		Long id = ownedEventId();

		mockMvc.perform(put("/api/events/" + id).header(HttpHeaders.AUTHORIZATION, adminToken)
						.contentType(MediaType.APPLICATION_JSON).content(updateJson()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Renamed"));
	}

	@Test
	void anotherOrganizerCannotPublishSomeoneElsesEvent() throws Exception {
		Long id = ownedEventId();

		mockMvc.perform(post("/api/events/" + id + "/publish")
						.header(HttpHeaders.AUTHORIZATION, otherOrganizerToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void publishingTwiceIsAConflict() throws Exception {
		Long id = ownedEventId();

		mockMvc.perform(post("/api/events/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.published").value(true));

		mockMvc.perform(post("/api/events/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, ownerToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EVENT_ALREADY_PUBLISHED"));
	}

	@Test
	void updatingAMissingEventIsNotFound() throws Exception {
		mockMvc.perform(put("/api/events/999999").header(HttpHeaders.AUTHORIZATION, adminToken)
						.contentType(MediaType.APPLICATION_JSON).content(updateJson()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void createRejectsInvalidFieldsWithPerFieldErrors() throws Exception {
		mockMvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(eventJson("", ENDS, STARTS, 0)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[?(@.field == 'title')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field == 'capacity')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field == 'endsAfterStarts')]").exists());
	}

	@Test
	void createRequiresAuthentication() throws Exception {
		mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON)
						.content(eventJson("Concert", STARTS, ENDS, 500)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}
}
