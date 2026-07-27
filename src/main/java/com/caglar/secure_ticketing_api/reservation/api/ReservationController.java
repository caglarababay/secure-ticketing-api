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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
	@Operation(summary = "Hold seats on a published event",
			description = """
					Seats are held immediately, before payment, and the reservation \
					starts as PENDING. An unconfirmed hold expires and returns its \
					seats.

					Requires an `Idempotency-Key`. Send the same key on every retry of \
					the same intent: a repeat returns the original reservation with \
					`X-Idempotent-Replay: true` rather than taking a second set of seats.
					""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Seats held; reservation is PENDING",
					headers = @Header(name = "X-Idempotent-Replay",
							description = "Present and `true` when this is a replay of an earlier "
									+ "request rather than a new reservation",
							schema = @Schema(type = "boolean"))),
			@ApiResponse(responseCode = "400",
					description = "Invalid seat count, or a missing/unusable Idempotency-Key",
					content = @Content),
			@ApiResponse(responseCode = "401", description = "No token, or an expired one",
					content = @Content),
			@ApiResponse(responseCode = "404", description = "No such event", content = @Content),
			@ApiResponse(responseCode = "409",
					description = "Event not published, not enough seats left, or an "
							+ "earlier request with this key is still running",
					content = @Content),
			@ApiResponse(responseCode = "422",
					description = "This Idempotency-Key was already used with a different request",
					content = @Content),
			@ApiResponse(responseCode = "429", description = "Too many reservations",
					content = @Content,
					headers = {
							@Header(name = "Retry-After", description = "Seconds to wait",
									schema = @Schema(type = "integer")),
							@Header(name = "X-RateLimit-Remaining",
									description = "Requests left in the window",
									schema = @Schema(type = "integer")) }) })
	ReservationResponse create(@PathVariable Long eventId,
			@Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true,
					description = "A UUID identifying this attempt. Reuse it when retrying.")
			@Valid @RequestBody CreateReservationRequest request,
			@AuthenticationPrincipal String userId) {

		return ReservationResponse.from(
				reservationService.create(eventId, Long.valueOf(userId), request.seats()));
	}

	@PostMapping("/api/reservations/{id}/confirm")
	@Operation(summary = "Confirm a pending reservation",
			description = "Only the reservation's owner, or an ADMIN. Confirming does not "
					+ "change the seat count — the seats were already held.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Reservation is now CONFIRMED"),
			@ApiResponse(responseCode = "401", description = "No token, or an expired one",
					content = @Content),
			@ApiResponse(responseCode = "403", description = "Not the owner",
					content = @Content),
			@ApiResponse(responseCode = "404", description = "No such reservation",
					content = @Content),
			@ApiResponse(responseCode = "409", description = "Not a legal transition from its current state",
					content = @Content) })
	ReservationResponse confirm(@PathVariable Long id,
			@AuthenticationPrincipal String userId, Authentication authentication) {

		return ReservationResponse.from(
				reservationService.confirm(id, Long.valueOf(userId), isAdmin(authentication)));
	}

	@PostMapping("/api/reservations/{id}/cancel")
	@Operation(summary = "Cancel a reservation and release its seats",
			description = "Works from PENDING or CONFIRMED — a confirmed ticket stays "
					+ "refundable. Cancelling is what returns seats to the pool.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Reservation is now CANCELLED"),
			@ApiResponse(responseCode = "401", description = "No token, or an expired one",
					content = @Content),
			@ApiResponse(responseCode = "403", description = "Not the owner",
					content = @Content),
			@ApiResponse(responseCode = "404", description = "No such reservation",
					content = @Content),
			@ApiResponse(responseCode = "409", description = "Already cancelled",
					content = @Content) })
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
