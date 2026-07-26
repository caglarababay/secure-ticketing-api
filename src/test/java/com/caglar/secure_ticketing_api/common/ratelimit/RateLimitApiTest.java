package com.caglar.secure_ticketing_api.common.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
		"ratelimit.auth.capacity=3",
		"ratelimit.auth.window=1m" })
class RateLimitApiTest {

	private static final AtomicInteger ADDRESSES = new AtomicInteger();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private RequestPostProcessor client;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		users.save(new User("throttled@test.com", passwordEncoder.encode("secret123"),
				EnumSet.of(Role.CUSTOMER), Instant.now()));

		String address = "10.1.0." + ADDRESSES.incrementAndGet();
		client = request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}

	@Test
	void callsWithinTheBudgetGetThrough() throws Exception {
		for (int i = 0; i < 3; i++) {
			login().andExpect(status().isOk());
		}
	}

	@Test
	void theCallAfterTheBudgetIsRefusedWithTheSharedErrorBody() throws Exception {
		exhaustAuthBudget();

		login()
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
				.andExpect(jsonPath("$.status").value(429))
				.andExpect(jsonPath("$.timestamp").exists())
				.andExpect(jsonPath("$.path").value("/api/auth/login"));
	}

	@Test
	void aRefusalSaysWhenToComeBack() throws Exception {
		exhaustAuthBudget();

		login()
				.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
				.andExpect(header().string("X-RateLimit-Remaining", "0"));
	}

	@Test
	void everyAnswerCarriesTheBudgetHeaders() throws Exception {
		login()
				.andExpect(header().string("X-RateLimit-Limit", "3"))
				.andExpect(header().string("X-RateLimit-Remaining", "2"))
				.andExpect(header().exists("X-RateLimit-Reset"));
	}

	@Test
	void theDraftHeaderIsSentToo() throws Exception {
		login().andExpect(header().string("RateLimit", "\"auth\";r=2;t=60"));
	}

	@Test
	void aRefusedLoginStillCountsAgainstTheBudget() throws Exception {
		wrongPassword().andExpect(status().isUnauthorized());
		wrongPassword().andExpect(status().isUnauthorized());
		wrongPassword().andExpect(status().isUnauthorized());

		wrongPassword()
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
	}

	@Test
	void theAuthEndpointsShareOneBudget() throws Exception {
		exhaustAuthBudget();

		mockMvc.perform(post("/api/auth/register").with(client)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"new@test.com\",\"password\":\"secret123\"}"))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	void separateClientsHaveSeparateBudgets() throws Exception {
		exhaustAuthBudget();
		login().andExpect(status().isTooManyRequests());

		mockMvc.perform(post("/api/auth/login")
						.with(request -> {
							request.setRemoteAddr("10.9.9.9");
							return request;
						})
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"throttled@test.com\",\"password\":\"secret123\"}"))
				.andExpect(status().isOk());
	}

	// --- counted before anyone is identified -------------------------------------

	@Test
	void attemptsWithNoCredentialsAtAllAreCounted() throws Exception {
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/auth/login").with(client)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"email\":\"ghost@test.com\",\"password\":\"guess\"}"))
					.andExpect(status().isUnauthorized());
		}

		mockMvc.perform(post("/api/auth/login").with(client)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"ghost@test.com\",\"password\":\"guess\"}"))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	void attemptsWithARejectedTokenAreCountedRatherThanWavedThrough() throws Exception {
		for (int i = 0; i < 3; i++) {
			refreshWithGarbage().andExpect(status().isUnauthorized());
		}

		refreshWithGarbage()
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
	}

	@Test
	void unthrottledEndpointsAreLeftAlone() throws Exception {
		exhaustAuthBudget();

		mockMvc.perform(get("/api/events/public").with(client))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("X-RateLimit-Limit"));
	}

	private void exhaustAuthBudget() throws Exception {
		for (int i = 0; i < 3; i++) {
			login();
		}
	}

	private ResultActions login() throws Exception {
		return mockMvc.perform(post("/api/auth/login").with(client)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"throttled@test.com\",\"password\":\"secret123\"}"));
	}

	private ResultActions refreshWithGarbage() throws Exception {
		return mockMvc.perform(post("/api/auth/refresh").with(client)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"not-a-real-token\"}"));
	}

	private ResultActions wrongPassword() throws Exception {
		return mockMvc.perform(post("/api/auth/login").with(client)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"throttled@test.com\",\"password\":\"wrong-password\"}"));
	}
}
