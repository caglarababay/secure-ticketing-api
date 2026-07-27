package com.caglar.secure_ticketing_api.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.caglar.secure_ticketing_api.audit.AuditLogTestSupport;
import com.caglar.secure_ticketing_api.audit.domain.AuditAction;
import com.caglar.secure_ticketing_api.audit.domain.AuditLog;
import com.caglar.secure_ticketing_api.audit.domain.AuditLogRepository;
import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.auth.service.JwtService;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUserApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private AuditLogRepository auditLogs;

	@Autowired
	private AuditLogTestSupport auditTrail;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	private User admin;
	private String adminToken;
	private String customerToken;
	private String organizerToken;

	@BeforeEach
	void setUp() {
		auditTrail.clear();
		users.deleteAll();

		admin = createUser("adminapi-admin@test.com", Role.ADMIN);
		adminToken = "Bearer " + jwtService.createAccessToken(admin);
		customerToken = "Bearer " + jwtService.createAccessToken(createUser("adminapi-customer@test.com", Role.CUSTOMER));
		organizerToken = "Bearer " + jwtService.createAccessToken(createUser("adminapi-organizer@test.com", Role.ORGANIZER));
	}

	// --- what an admin can do -----------------------------------------------------

	@Test
	void anAdminOpensAnOrganizerAccount() throws Exception {
		create(adminToken, "neworganizer@test.com", "ORGANIZER")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value("neworganizer@test.com"))
				.andExpect(jsonPath("$.roles[0]").value("ORGANIZER"))
				.andExpect(jsonPath("$.id").exists());

		assertThat(users.findByEmail("neworganizer@test.com").orElseThrow().getRoles())
				.containsExactly(Role.ORGANIZER);
	}

	@Test
	void severalRolesCanBeGrantedAtOnce() throws Exception {
		mockMvc.perform(post("/api/admin/users")
						.header(HttpHeaders.AUTHORIZATION, adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"both@test.com","password":"secret123",
								 "roles":["ORGANIZER","CUSTOMER"]}
								"""))
				.andExpect(status().isCreated());

		assertThat(users.findByEmail("both@test.com").orElseThrow().getRoles())
				.containsExactlyInAnyOrder(Role.ORGANIZER, Role.CUSTOMER);
	}

	@Test
	void theCreatedAccountCanSignIn() throws Exception {
		create(adminToken, "signsin@test.com", "ORGANIZER").andExpect(status().isCreated());

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"signsin@test.com\",\"password\":\"secret123\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	// --- who cannot ------------------------------------------------------------------

	@Test
	void aCustomerCannot() throws Exception {
		create(customerToken, "sneaky@test.com", "ADMIN").andExpect(status().isForbidden());

		assertThat(users.findByEmail("sneaky@test.com")).isEmpty();
	}

	@Test
	void anOrganizerCannot() throws Exception {
		create(organizerToken, "sneaky@test.com", "ORGANIZER").andExpect(status().isForbidden());

		assertThat(users.findByEmail("sneaky@test.com")).isEmpty();
	}

	@Test
	void anAnonymousCallerIsUnauthorisedRatherThanForbidden() throws Exception {
		mockMvc.perform(post("/api/admin/users")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("sneaky@test.com", "ADMIN")))
				.andExpect(status().isUnauthorized());
	}

	// --- validation --------------------------------------------------------------------

	@Test
	void aKnownAddressIsTheSameConflictAsRegistration() throws Exception {
		create(adminToken, "adminapi-admin@test.com", "ORGANIZER")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
	}

	@Test
	void rolesCannotBeEmpty() throws Exception {
		mockMvc.perform(post("/api/admin/users")
						.header(HttpHeaders.AUTHORIZATION, adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"noroles@test.com\",\"password\":\"secret123\",\"roles\":[]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void aShortPasswordIsRejected() throws Exception {
		mockMvc.perform(post("/api/admin/users")
						.header(HttpHeaders.AUTHORIZATION, adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"short@test.com\",\"password\":\"abc\",\"roles\":[\"CUSTOMER\"]}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void theResponseNeverCarriesTheCredentials() throws Exception {
		String body = create(adminToken, "quiet@test.com", "ORGANIZER")
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		assertThat(body)
				.doesNotContain("secret123")
				.doesNotContain("password")
				.doesNotContain("$2a$");
	}

	@Test
	void theAdminWhoDidItIsRecorded() throws Exception {
		create(adminToken, "recorded@test.com", "ORGANIZER").andExpect(status().isCreated());

		Long createdId = users.findByEmail("recorded@test.com").orElseThrow().getId();
		AuditLog entry = auditLogs.findAll().getFirst();
		assertThat(auditLogs.findAll()).hasSize(1);
		assertThat(entry.getAction()).isEqualTo(AuditAction.ADMIN_CREATED_USER);
		assertThat(entry.getActorId())
				.as("the admin is answerable, not the account they opened")
				.isEqualTo(admin.getId());
		assertThat(entry.getResourceId()).isEqualTo(createdId);
	}

	@Test
	void aRefusedRequestIsNotRecorded() throws Exception {
		create(customerToken, "sneaky@test.com", "ADMIN").andExpect(status().isForbidden());

		assertThat(auditLogs.count()).isZero();
	}

	private ResultActions create(String token, String email, String role) throws Exception {
		return mockMvc.perform(post("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(email, role)));
	}

	private String body(String email, String role) {
		return "{\"email\":\"%s\",\"password\":\"secret123\",\"roles\":[\"%s\"]}"
				.formatted(email, role);
	}

	private User createUser(String email, Role role) {
		return users.save(new User(email, passwordEncoder.encode("secret123"),
				EnumSet.of(role), Instant.now()));
	}
}
