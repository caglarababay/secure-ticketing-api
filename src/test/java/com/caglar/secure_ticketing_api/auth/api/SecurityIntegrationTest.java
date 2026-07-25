package com.caglar.secure_ticketing_api.auth.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.caglar.secure_ticketing_api.auth.domain.UserRepository;

import tools.jackson.databind.ObjectMapper;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SecurityIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void clearUsers() {
		users.deleteAll();
	}

	private TokenResponse registerAndLogin() throws Exception {
		String body = "{\"email\":\"secure@test.com\",\"password\":\"secret123\"}";
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated());

		String response = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		return objectMapper.readValue(response, TokenResponse.class);
	}

	@Test
	void requestWithoutTokenIsUnauthorizedOnTheSharedErrorContract() throws Exception {
		mockMvc.perform(get("/api/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.timestamp").exists())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.path").value("/api/auth/me"))
				.andExpect(jsonPath("$.errors").doesNotExist());
	}

	@Test
	void validAccessTokenGrantsAccess() throws Exception {
		TokenResponse tokens = registerAndLogin();

		mockMvc.perform(get("/api/auth/me")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("secure@test.com"))
				.andExpect(jsonPath("$.roles").value("CUSTOMER"))
				.andExpect(jsonPath("$.lastLoginAt").isNotEmpty());
	}

	@Test
	void refreshTokenCannotBeUsedAsAnAccessToken() throws Exception {
		TokenResponse tokens = registerAndLogin();

		mockMvc.perform(get("/api/auth/me")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.refreshToken()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void malformedTokenIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/auth/me")
						.header(HttpHeaders.AUTHORIZATION, "Bearer not.a.token"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void tokenWithoutBearerPrefixIsIgnored() throws Exception {
		TokenResponse tokens = registerAndLogin();

		mockMvc.perform(get("/api/auth/me")
						.header(HttpHeaders.AUTHORIZATION, tokens.accessToken()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void refreshEndpointIssuesAWorkingAccessToken() throws Exception {
		TokenResponse tokens = registerAndLogin();

		String refreshed = mockMvc.perform(post("/api/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"refreshToken\":\"%s\"}".formatted(tokens.refreshToken())))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		TokenResponse newTokens = objectMapper.readValue(refreshed, TokenResponse.class);

		mockMvc.perform(get("/api/auth/me")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + newTokens.accessToken()))
				.andExpect(status().isOk());
	}

	@Test
	void authEndpointsStayOpenWithoutAToken() throws Exception {
		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"nobody@test.com\",\"password\":\"secret123\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}
}
