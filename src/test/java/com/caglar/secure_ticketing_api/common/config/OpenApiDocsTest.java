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
class OpenApiDocsTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	private String token;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		User user = users.save(new User("docs@test.com", passwordEncoder.encode("secret123"),
				EnumSet.of(Role.CUSTOMER), Instant.now()));
		token = "Bearer " + jwtService.createAccessToken(user);
	}

	@Test
	void theSchemaIsNotPublic() throws Exception {
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
	}

	@Test
	void swaggerUiIsNotPublicEither() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
	}

	@Test
	void anAuthenticatedCallerGetsTheSchema() throws Exception {
		mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.")))
				.andExpect(jsonPath("$.info.title").value("Secure Ticketing API"));
	}

	@Test
	void bearerAuthenticationIsDescribed() throws Exception {
		mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(jsonPath("$.components.securitySchemes['bearer-jwt'].type").value("http"))
				.andExpect(jsonPath("$.components.securitySchemes['bearer-jwt'].scheme").value("bearer"))
				.andExpect(jsonPath("$.components.securitySchemes['bearer-jwt'].bearerFormat").value("JWT"));
	}

	@Test
	void everyEndpointIsListed() throws Exception {
		mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(jsonPath("$.paths['/api/auth/login']").exists())
				.andExpect(jsonPath("$.paths['/api/events/{eventId}/reservations']").exists())
				.andExpect(jsonPath("$.paths['/api/admin/users']").exists());
	}

	@Test
	void theIdempotencyKeyHeaderIsDocumentedAsRequired() throws Exception {
		mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(jsonPath(
						"$.paths['/api/events/{eventId}/reservations'].post.parameters[?(@.name == 'Idempotency-Key')].required")
						.value(org.hamcrest.Matchers.hasItem(true)));
	}

	@Test
	void theThrottledAndConflictingOutcomesAreDocumented() throws Exception {
		mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(jsonPath("$.paths['/api/events/{eventId}/reservations'].post.responses.429").exists())
				.andExpect(jsonPath("$.paths['/api/events/{eventId}/reservations'].post.responses.422").exists());
	}

	@Test
	void operationsCarryASummary() throws Exception {
		mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(jsonPath("$.paths['/api/auth/login'].post.summary").isNotEmpty())
				.andExpect(jsonPath("$.paths['/api/admin/users'].post.summary").isNotEmpty());
	}
}
