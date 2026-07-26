package com.caglar.secure_ticketing_api.auth.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;

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


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ExpiredTokenApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private JwtProperties jwtProperties;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private User user;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		user = users.save(new User("expired@test.com", passwordEncoder.encode("secret123"),
				EnumSet.of(Role.CUSTOMER), Instant.now()));
	}

	@Test
	void anExpiredAccessTokenIsRefusedOnTheSharedErrorContract() throws Exception {
		mockMvc.perform(get("/api/auth/me")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + staleAccessToken()))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.timestamp").exists())
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.path").value("/api/auth/me"));
	}

	@Test
	void anExpiredTokenIsUnauthorizedRatherThanForbidden() throws Exception {
		mockMvc.perform(post("/api/events")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + staleAccessToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"T","description":"d","venue":"V",
								 "startsAt":"2027-07-01T18:00:00Z","endsAt":"2027-07-01T22:00:00Z","capacity":10}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void anExpiredRefreshTokenIsRefusedToo() throws Exception {
		String staleRefresh = backdatedService().createRefreshToken(user);

		mockMvc.perform(post("/api/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"refreshToken\":\"%s\"}".formatted(staleRefresh)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
	}

	private String staleAccessToken() {
		return backdatedService().createAccessToken(user);
	}

	private JwtService backdatedService() {
		Clock lastYear = Clock.fixed(Instant.now().minus(Duration.ofDays(365)), ZoneOffset.UTC);
		return new JwtService(jwtProperties, lastYear);
	}
}
