package com.caglar.secure_ticketing_api.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
class OpenApiConfig {

	private static final String BEARER = "bearer-jwt";

	@Bean
	OpenAPI apiDescription() {
		return new OpenAPI()
				.info(new Info()
						.title("Secure Ticketing API")
						.version("v1")
						.description("Event ticketing and seat reservation."))
				.components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
						.type(SecurityScheme.Type.HTTP)
						.scheme("bearer")
						.bearerFormat("JWT")
						.description("Access token from /api/auth/login")))
				.addSecurityItem(new SecurityRequirement().addList(BEARER));
	}
}
