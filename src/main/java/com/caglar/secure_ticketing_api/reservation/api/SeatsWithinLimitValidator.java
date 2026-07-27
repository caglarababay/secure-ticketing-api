package com.caglar.secure_ticketing_api.reservation.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;


class SeatsWithinLimitValidator implements ConstraintValidator<SeatsWithinLimit, Integer> {

	private final ReservationRequestLimits limits;

	SeatsWithinLimitValidator(ReservationRequestLimits limits) {
		this.limits = limits;
	}

	@Override
	public boolean isValid(Integer seats, ConstraintValidatorContext context) {
		if (seats == null || seats <= limits.maxSeats()) {
			return true;
		}

		context.disableDefaultConstraintViolation();
		context.buildConstraintViolationWithTemplate(
				"must not exceed %d seats per reservation".formatted(limits.maxSeats()))
				.addConstraintViolation();
		return false;
	}
}
