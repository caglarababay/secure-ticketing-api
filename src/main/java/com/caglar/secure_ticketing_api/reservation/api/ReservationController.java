package com.caglar.secure_ticketing_api.reservation.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.caglar.secure_ticketing_api.reservation.service.ReservationService;

import jakarta.validation.Valid;


@RestController
public class ReservationController {

	private static final String ROLE_ADMIN = "ROLE_ADMIN";

	private final ReservationService reservationService;

	ReservationController(ReservationService reservationService) {
		this.reservationService = reservationService;
	}

	@PostMapping("/api/events/{eventId}/reservations")
	@ResponseStatus(HttpStatus.CREATED)
	ReservationResponse create(@PathVariable Long eventId,
			@Valid @RequestBody CreateReservationRequest request,
			@AuthenticationPrincipal String userId) {

		return ReservationResponse.from(
				reservationService.create(eventId, Long.valueOf(userId), request.seats()));
	}

	@PostMapping("/api/reservations/{id}/confirm")
	ReservationResponse confirm(@PathVariable Long id,
			@AuthenticationPrincipal String userId, Authentication authentication) {

		return ReservationResponse.from(
				reservationService.confirm(id, Long.valueOf(userId), isAdmin(authentication)));
	}

	@PostMapping("/api/reservations/{id}/cancel")
	ReservationResponse cancel(@PathVariable Long id,
			@AuthenticationPrincipal String userId, Authentication authentication) {

		return ReservationResponse.from(
				reservationService.cancel(id, Long.valueOf(userId), isAdmin(authentication)));
	}

	private boolean isAdmin(Authentication authentication) {
		return authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.anyMatch(ROLE_ADMIN::equals);
	}
}
