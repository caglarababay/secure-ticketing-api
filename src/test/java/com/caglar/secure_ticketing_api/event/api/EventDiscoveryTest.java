package com.caglar.secure_ticketing_api.event.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
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
class EventDiscoveryTest {

	private static final Instant JULY = Instant.parse("2027-07-01T18:00:00Z");
	private static final Instant SEPTEMBER = Instant.parse("2027-09-01T18:00:00Z");

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

	private String customerToken;

	@BeforeEach
	void setUp() {
		events.deleteAll();
		users.deleteAll();
		var support = new EventTestSupport(users, passwordEncoder, jwtService);
		User owner = support.createUser("owner@test.com", Role.ORGANIZER);
		customerToken = support.bearerFor(support.createUser("customer@test.com", Role.CUSTOMER));

		events.save(published(owner.getId(), "Summer Festival", JULY));
		events.save(published(owner.getId(), "Autumn Festival", SEPTEMBER));
		
		events.save(new Event(owner.getId(), "Secret Festival", "Studio", JULY, JULY.plus(Duration.ofHours(2)), 10));
	}

	private static Event published(Long ownerId, String title, Instant startsAt) {
		Event event = new Event(ownerId, title, "Arena", startsAt, startsAt.plus(Duration.ofHours(3)), 100);
		event.publish();
		return event;
	}

	@Test
	void discoveryIsReachableWithoutAToken() throws Exception {
		mockMvc.perform(get("/api/events/public"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", Matchers.hasSize(2)))
				.andExpect(jsonPath("$.totalElements").value(2));
	}

	@Test
	void discoveryHidesDrafts() throws Exception {
		mockMvc.perform(get("/api/events/public"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.title == 'Secret Festival')]").doesNotExist())
				.andExpect(jsonPath("$.content[?(@.published == false)]").doesNotExist());
	}

	@Test
	void searchIsCaseInsensitiveOnTitle() throws Exception {
		mockMvc.perform(get("/api/events/public").param("q", "SUMMER"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].title").value("Summer Festival"));
	}

	@Test
	void searchWithNoMatchReturnsAnEmptyPage() throws Exception {
		mockMvc.perform(get("/api/events/public").param("q", "nothing-matches"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", Matchers.hasSize(0)))
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void blankSearchTermReturnsEverythingPublished() throws Exception {
		mockMvc.perform(get("/api/events/public").param("q", "  "))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2));
	}

	@Test
	void fromFiltersOutEarlierEvents() throws Exception {
		mockMvc.perform(get("/api/events/public").param("from", "2027-08-01T00:00:00Z"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].title").value("Autumn Festival"));
	}

	@Test
	void toFiltersOutLaterEvents() throws Exception {
		mockMvc.perform(get("/api/events/public").param("to", "2027-08-01T00:00:00Z"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].title").value("Summer Festival"));
	}

	@Test
	void fromAndToNarrowToARange() throws Exception {
		mockMvc.perform(get("/api/events/public")
						.param("from", "2027-06-01T00:00:00Z")
						.param("to", "2027-08-01T00:00:00Z"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void pageMetadataIsReported() throws Exception {
		mockMvc.perform(get("/api/events/public").param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", Matchers.hasSize(1)))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(1))
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.totalPages").value(2));
	}

	@Test
	void authenticatedListingRequiresAToken() throws Exception {
		mockMvc.perform(get("/api/events"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, customerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3));
	}

	@Test
	void ownerIdNarrowsTheListingToThatOwner() throws Exception {
		Long otherOwnerId = users.findByEmail("customer@test.com").orElseThrow().getId();
		events.save(new Event(otherOwnerId, "Someone Else's", "Hall", JULY,
				JULY.plus(Duration.ofHours(1)), 20));

		mockMvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, customerToken)
						.param("ownerId", String.valueOf(otherOwnerId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].title").value("Someone Else's"));
	}

	@Test
	void omittedOwnerIdReturnsEveryEvent() throws Exception {
		mockMvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, customerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3));
	}
}
