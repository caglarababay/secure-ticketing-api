package com.caglar.secure_ticketing_api.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.auth.service.JwtService;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorSecurityTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	private String adminToken;
	private String customerToken;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		adminToken = tokenFor("actuator-admin@test.com", Role.ADMIN);
		customerToken = tokenFor("actuator-customer@test.com", Role.CUSTOMER);
	}

	@Test
	void healthAnswersAnonymously() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void healthTellsAnonymousCallersNothingBeyondUpOrDown() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components").doesNotExist());
	}

	@Test
	void healthTellsAnAdminWhichPartsAreUp() throws Exception {
		mockMvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components").exists())
				.andExpect(jsonPath("$.components.db").exists());
	}

	@Test
	void healthTellsACustomerNothingExtraEither() throws Exception {
		mockMvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, customerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components").doesNotExist());
	}

	@Test
	void infoIsOpen() throws Exception {
		mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
	}

	// --- metrics: admin only --------------------------------------------------------

	@Test
	void metricsRefusesAnonymousCallers() throws Exception {
		mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
	}

	@Test
	void metricsRefusesNonAdmins() throws Exception {
		mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, customerToken))
				.andExpect(status().isForbidden());
	}

	@Test
	void metricsIsOpenToAdmins() throws Exception {
		mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.names").isArray());
	}

	@Test
	void envIsNotPublishedEvenForAnAdmin() throws Exception {
		mockMvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isNotFound());
	}

	@Test
	void heapdumpIsNotPublishedEvenForAnAdmin() throws Exception {
		mockMvc.perform(get("/actuator/heapdump").header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isNotFound());
	}

	@Test
	void threaddumpAndBeansAreNotPublishedEither() throws Exception {
		mockMvc.perform(get("/actuator/threaddump").header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/beans").header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isNotFound());
	}

	@Test
	void unpublishedEndpointsAreNotReachableAnonymouslyEither() throws Exception {
		mockMvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
		mockMvc.perform(get("/actuator/heapdump")).andExpect(status().is4xxClientError());
	}

	// --- the throttle does not apply here ---------------------------------------------

	@Test
	void healthIsNotRateLimited() throws Exception {
		for (int i = 0; i < 20; i++) {
			mockMvc.perform(get("/actuator/health"))
					.andExpect(status().isOk())
					.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
							.header().doesNotExist("X-RateLimit-Limit"));
		}
	}

	@Test
	void actuatorDoesNotDemandAnIdempotencyKey() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
		mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk());
	}

	private String tokenFor(String email, Role role) {
		User user = users.save(new User(email, passwordEncoder.encode("secret123"),
				EnumSet.of(role), Instant.now()));
		return "Bearer " + jwtService.createAccessToken(user);
	}
}
