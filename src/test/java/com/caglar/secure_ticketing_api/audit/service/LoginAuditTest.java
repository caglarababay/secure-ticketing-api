package com.caglar.secure_ticketing_api.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginAuditTest {

	private static final String PASSWORD = "secret123";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AuditLogRepository auditLogs;

	@Autowired
	private AuditLogTestSupport auditTrail;

	@Autowired
	private UserRepository users;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private Long userId;

	@BeforeEach
	void setUp() {
		auditTrail.clear();
		users.deleteAll();

		userId = users.save(new User("known@test.com", passwordEncoder.encode(PASSWORD),
				EnumSet.of(Role.CUSTOMER), Instant.now())).getId();
	}

	// --- the trail ---------------------------------------------------------------

	@Test
	void aSuccessfulSignInIsRecorded() throws Exception {
		login("known@test.com", PASSWORD).andExpect(status().isOk());

		AuditLog entry = onlyEntry();
		assertThat(entry.getAction()).isEqualTo(AuditAction.LOGIN_SUCCEEDED);
		assertThat(entry.getResourceType()).isEqualTo(AuditResource.USER);
		assertThat(entry.getActorId()).isEqualTo(userId);
		assertThat(entry.getResourceId()).isEqualTo(userId);
	}

	@Test
	void aWrongPasswordIsRecordedDespiteTheRollback() throws Exception {
		login("known@test.com", "wrong").andExpect(status().isUnauthorized());

		AuditLog entry = onlyEntry();
		assertThat(entry.getAction()).isEqualTo(AuditAction.LOGIN_FAILED);
		assertThat(entry.getActorId())
				.as("we know which account was targeted")
				.isEqualTo(userId);
	}

	@Test
	void anAttemptOnAnUnknownAddressIsRecordedWithNoActor() throws Exception {
		login("nobody@test.com", PASSWORD).andExpect(status().isUnauthorized());

		AuditLog entry = onlyEntry();
		assertThat(entry.getAction()).isEqualTo(AuditAction.LOGIN_FAILED);
		assertThat(entry.getActorId())
				.as("nobody to attribute it to, but the attempt still matters")
				.isNull();
	}

	/** What brute force looks like in the table. */
	@Test
	void repeatedFailuresAccumulate() throws Exception {
		for (int i = 0; i < 5; i++) {
			login("known@test.com", "wrong-" + i).andExpect(status().isUnauthorized());
		}

		assertThat(auditLogs.findByActionOrderByCreatedAtDesc(AuditAction.LOGIN_FAILED)).hasSize(5);
	}

	@Test
	void theCallersAddressIsRecordedOnAFailure() throws Exception {
		mockMvc.perform(post("/api/auth/login")
						.with(request -> {
							request.setRemoteAddr("198.51.100.4");
							return request;
						})
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("known@test.com", "wrong")))
				.andExpect(status().isUnauthorized());

		assertThat(onlyEntry().getIp()).isEqualTo("198.51.100.4");
	}

	@Test
	void bothFailuresAnswerIdentically() throws Exception {
		String unknownAddress = login("nobody@test.com", PASSWORD)
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();

		String wrongPassword = login("known@test.com", "wrong")
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();

		assertThat(withoutTimestamp(wrongPassword)).isEqualTo(withoutTimestamp(unknownAddress));
	}

	@Test
	void neitherFailureNamesTheAccount() throws Exception {
		for (String body : new String[] {
				login("nobody@test.com", PASSWORD).andReturn().getResponse().getContentAsString(),
				login("known@test.com", "wrong").andReturn().getResponse().getContentAsString() }) {

			assertThat(body)
					.contains("INVALID_CREDENTIALS")
					.contains("Invalid email or password.")
					.doesNotContain("known@test.com")
					.doesNotContain("nobody@test.com");
		}
	}

	private String withoutTimestamp(String json) {
		return json.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"?\"");
	}

	private AuditLog onlyEntry() {
		assertThat(auditLogs.findAll()).hasSize(1);
		return auditLogs.findAll().getFirst();
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(email, password)));
	}

	private String body(String email, String password) {
		return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
	}
}
