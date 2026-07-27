package com.caglar.secure_ticketing_api.reservation.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.auth.service.JwtService;
import com.caglar.secure_ticketing_api.event.domain.Event;
import com.caglar.secure_ticketing_api.event.domain.EventRepository;
import com.caglar.secure_ticketing_api.reservation.domain.ReservationRepository;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "reservation.request.max-seats=3")
@Transactional
class ConfigurableSeatLimitTest {

	private static final Instant STARTS = Instant.parse("2027-07-01T18:00:00Z");
	private static final Instant ENDS = STARTS.plus(Duration.ofHours(4));

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

	private String customerToken;
	private Long eventId;

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		events.deleteAll();
		users.deleteAll();

		User customer = users.save(new User("seatlimit@test.com", passwordEncoder.encode("secret123"),
				EnumSet.of(Role.CUSTOMER), Instant.now()));
		customerToken = "Bearer " + jwtService.createAccessToken(customer);

		Event event = new Event(customer.getId(), "Configurable", "Arena", STARTS, ENDS, 100);
		event.publish();
		eventId = events.save(event).getId();
	}

	@Test
	void theConfiguredLimitIsAccepted() throws Exception {
		reserve(3).andExpect(status().isCreated());
	}

	@Test
	void oneSeatBeyondTheConfiguredLimitIsRejected() throws Exception {
		reserve(4)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[?(@.field == 'seats')]").exists());
	}

	@Test
	void theMessageNamesTheConfiguredLimit() throws Exception {
		reserve(4).andExpect(jsonPath("$.errors[?(@.field == 'seats')].message")
				.value(hasItem(containsString("3"))));
	}

	@Test
	void theOldHardCodedCeilingNoLongerApplies() throws Exception {
		reserve(50).andExpect(status().isBadRequest());
	}

	private ResultActions reserve(int seats) throws Exception {
		return mockMvc.perform(post("/api/events/" + eventId + "/reservations")
				.header(HttpHeaders.AUTHORIZATION, customerToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"seats\":%d}".formatted(seats)));
	}
}
