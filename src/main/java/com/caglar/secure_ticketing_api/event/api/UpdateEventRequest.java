package com.caglar.secure_ticketing_api.event.api;

import java.time.Instant;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;


public record UpdateEventRequest(

		@NotBlank @Size(max = 200)
		String title,

		@NotBlank @Size(max = 200)
		String venue,

		@NotNull @Future
		Instant startsAt,

		@NotNull @Future
		Instant endsAt,

		@Positive
		int capacity) {

	@AssertTrue(message = "endsAt must be after startsAt")
	public boolean isEndsAfterStarts() {
		return startsAt == null || endsAt == null || endsAt.isAfter(startsAt);
	}
}
