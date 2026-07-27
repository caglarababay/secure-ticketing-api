package com.caglar.secure_ticketing_api.event.api;

import java.time.Instant;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import com.caglar.secure_ticketing_api.common.api.PageResponse;
import com.caglar.secure_ticketing_api.event.service.EventService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/events")
public class EventController {

	private static final String ROLE_ADMIN = "ROLE_ADMIN";

	private final EventService eventService;

	EventController(EventService eventService) {
		this.eventService = eventService;
	}

	@Operation(summary = "Create a draft event")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Draft created"),
			@ApiResponse(responseCode = "401", description = "No token, or an expired one"),
			@ApiResponse(responseCode = "403", description = "CUSTOMER accounts cannot create events") })
	@PostMapping
	@PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
	@ResponseStatus(HttpStatus.CREATED)
	EventResponse create(@Valid @RequestBody CreateEventRequest request,
			@AuthenticationPrincipal String userId) {

		return EventResponse.from(eventService.create(request, Long.valueOf(userId)));
	}

	@Operation(summary = "Update an event")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated"),
			@ApiResponse(responseCode = "401", description = "No token, or an expired one"),
			@ApiResponse(responseCode = "403", description = "Not the owner"),
			@ApiResponse(responseCode = "404", description = "No such event"),
			@ApiResponse(responseCode = "409", description = "Capacity would fall below reserved seats") })
	@PutMapping("/{id}")
	@PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
	EventResponse update(@PathVariable Long id, @Valid @RequestBody UpdateEventRequest request,
			@AuthenticationPrincipal String userId, Authentication authentication) {

		return EventResponse.from(eventService.update(id, request,
				Long.valueOf(userId), isAdmin(authentication)));
	}

	@Operation(summary = "Publish a draft")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Published"),
			@ApiResponse(responseCode = "401", description = "No token, or an expired one"),
			@ApiResponse(responseCode = "403", description = "Not the owner"),
			@ApiResponse(responseCode = "404", description = "No such event"),
			@ApiResponse(responseCode = "409", description = "Already published") })
	@PostMapping("/{id}/publish")
	@PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
	EventResponse publish(@PathVariable Long id,
			@AuthenticationPrincipal String userId, Authentication authentication) {

		return EventResponse.from(eventService.publish(id, Long.valueOf(userId), isAdmin(authentication)));
	}

	@Operation(summary = "List events, drafts included")
	@GetMapping
	PageResponse<EventResponse> list(@RequestParam(required = false) Long ownerId,
			@PageableDefault(size = 20, sort = "startsAt") Pageable pageable) {

		return PageResponse.from(eventService.list(ownerId, pageable), EventResponse::from);
	}

	@Operation(summary = "Browse published events")
	@GetMapping("/public")
	PageResponse<EventResponse> discover(
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			@RequestParam(required = false) String q,
			@PageableDefault(size = 20, sort = "startsAt") Pageable pageable) {

		return PageResponse.from(eventService.discoverPublished(from, to, q, pageable),
				EventResponse::from);
	}

	private boolean isAdmin(Authentication authentication) {
		return authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.anyMatch(ROLE_ADMIN::equals);
	}
}
