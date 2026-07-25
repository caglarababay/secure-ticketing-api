package com.caglar.secure_ticketing_api.auth.api;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.caglar.secure_ticketing_api.auth.service.JwtService;
import com.caglar.secure_ticketing_api.common.error.ApiException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtService jwtService;

	JwtAuthenticationFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		String token = bearerToken(request);
		if (token != null) {
			authenticate(token);
		}
		chain.doFilter(request, response);
	}

	private void authenticate(String token) {
		try {
			Jwt jwt = jwtService.decode(token, true);
			List<SimpleGrantedAuthority> authorities = jwtService.rolesOf(jwt)
					.stream()
					.map(role -> new SimpleGrantedAuthority("ROLE_" + role))
					.toList();

			var authentication = new UsernamePasswordAuthenticationToken(
					jwt.getSubject(), null, authorities);
			SecurityContextHolder.getContext().setAuthentication(authentication);
		}
		catch (ApiException | IllegalArgumentException ex) {
			SecurityContextHolder.clearContext();
		}
	}

	private String bearerToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		return header != null && header.startsWith(BEARER_PREFIX)
				? header.substring(BEARER_PREFIX.length())
				: null;
	}
}
