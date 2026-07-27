package com.caglar.secure_ticketing_api.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GlobalExceptionHandlerTest.FailingProbeController.class)
class GlobalExceptionHandlerTest {

	private static final String SECRET_IN_MESSAGE = "jdbc://internal-host/creds";

	@Autowired
	private MockMvc mockMvc;

	@Test
	@WithMockUser
	void unmappedPathReturnsStandardErrorBody() throws Exception {
		mockMvc.perform(get("/api/no-such-endpoint"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.timestamp").exists())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.path").value("/api/no-such-endpoint"))
				.andExpect(jsonPath("$.errors").doesNotExist());
	}

	// --- what an unexpected failure looks like from outside -----------------------

	@Test
	@WithMockUser
	void anUnhandledFailureStillReturnsTheStandardBody() throws Exception {
		mockMvc.perform(get("/test-only/explode"))
				.andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.timestamp").exists())
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.path").value("/test-only/explode"));
	}

	@Test
	@WithMockUser
	void anUnhandledFailureLeaksNothingAboutItself() throws Exception {
		String body = mockMvc.perform(get("/test-only/explode"))
				.andExpect(status().isInternalServerError())
				.andReturn().getResponse().getContentAsString();

		assertThat(body)
				.as("no exception message")
				.doesNotContain(SECRET_IN_MESSAGE)
				.as("no exception type")
				.doesNotContain("IllegalStateException")
				.as("no stack frames")
				.doesNotContain("at com.caglar")
				.doesNotContain(".java:")
				.as("no framework internals")
				.doesNotContain("org.springframework");
	}

	@Test
	@WithMockUser
	void theMessageIsTheSameGenericOneEveryTime() throws Exception {
		mockMvc.perform(get("/test-only/explode"))
				.andExpect(jsonPath("$.message").value("An unexpected error occurred."));
	}

	@RestController
	static class FailingProbeController {

		@GetMapping("/test-only/explode")
		String explode() {
			throw new IllegalStateException("Connection failed to " + SECRET_IN_MESSAGE);
		}
	}
}
