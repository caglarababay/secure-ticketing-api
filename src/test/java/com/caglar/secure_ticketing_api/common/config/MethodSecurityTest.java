package com.caglar.secure_ticketing_api.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MethodSecurityTest.RoleProbeController.class)
class MethodSecurityTest {

	@Autowired
	private MockMvc mockMvc;

	@RestController
	static class RoleProbeController {

		@GetMapping("/test-only/admin")
		@PreAuthorize("hasRole('ADMIN')")
		String adminOnly() {
			return "admin";
		}

		@GetMapping("/test-only/organizer-or-admin")
		@PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
		String organizerOrAdmin() {
			return "organizer-or-admin";
		}
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void adminReachesAdminOnlyEndpoint() throws Exception {
		mockMvc.perform(get("/test-only/admin")).andExpect(status().isOk());
	}

	@Test
	@WithMockUser(roles = "CUSTOMER")
	void customerIsForbiddenFromAdminOnlyEndpointWithApiErrorBody() throws Exception {
		mockMvc.perform(get("/test-only/admin"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.timestamp").exists())
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.code").value("FORBIDDEN"))
				.andExpect(jsonPath("$.path").value("/test-only/admin"))
				.andExpect(jsonPath("$.errors").doesNotExist());
	}

	@Test
	@WithMockUser(roles = "ORGANIZER")
	void organizerReachesTheSharedEndpointButNotTheAdminOne() throws Exception {
		mockMvc.perform(get("/test-only/organizer-or-admin")).andExpect(status().isOk());
		mockMvc.perform(get("/test-only/admin")).andExpect(status().isForbidden());
	}

	@Test
	void anonymousRequestIsUnauthorized() throws Exception {
		mockMvc.perform(get("/test-only/admin"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}
}
