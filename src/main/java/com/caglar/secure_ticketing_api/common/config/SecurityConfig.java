package com.caglar.secure_ticketing_api.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.caglar.secure_ticketing_api.auth.api.ApiErrorAccessDeniedHandler;
import com.caglar.secure_ticketing_api.auth.api.ApiErrorAuthenticationEntryPoint;
import com.caglar.secure_ticketing_api.auth.api.JwtAuthenticationFilter;
import com.caglar.secure_ticketing_api.auth.service.JwtProperties;
import com.caglar.secure_ticketing_api.idempotency.api.IdempotencyFilter;


@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			JwtAuthenticationFilter jwtAuthenticationFilter,
			IdempotencyFilter idempotencyFilter,
			ApiErrorAuthenticationEntryPoint entryPoint,
			ApiErrorAccessDeniedHandler accessDeniedHandler) throws Exception {

		return http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh")
						.permitAll()
						.requestMatchers(HttpMethod.GET, "/api/events/public")
						.permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(entryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(idempotencyFilter, AuthorizationFilter.class)
				.build();
	}

	@Bean
	FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
			IdempotencyFilter filter) {

		FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
