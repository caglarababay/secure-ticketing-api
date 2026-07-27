package com.caglar.secure_ticketing_api.reservation.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * The seat count is at most the configured per-request maximum.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Constraint(validatedBy = SeatsWithinLimitValidator.class)
public @interface SeatsWithinLimit {

	String message() default "exceeds the maximum seats per reservation";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
