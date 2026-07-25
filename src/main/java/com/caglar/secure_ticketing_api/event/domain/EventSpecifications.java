package com.caglar.secure_ticketing_api.event.domain;

import java.time.Instant;

import org.springframework.data.jpa.domain.Specification;


public final class EventSpecifications {

	private EventSpecifications() {
	}

	public static Specification<Event> publishedOnly() {
		return (root, query, builder) -> builder.isTrue(root.get("published"));
	}

	public static Specification<Event> ownedBy(Long ownerId) {
		return ownerId == null
				? Specification.unrestricted()
				: (root, query, builder) -> builder.equal(root.get("ownerId"), ownerId);
	}

	public static Specification<Event> startingAtOrAfter(Instant from) {
		return from == null
				? Specification.unrestricted()
				: (root, query, builder) -> builder.greaterThanOrEqualTo(root.get("startsAt"), from);
	}

	public static Specification<Event> startingAtOrBefore(Instant to) {
		return to == null
				? Specification.unrestricted()
				: (root, query, builder) -> builder.lessThanOrEqualTo(root.get("startsAt"), to);
	}

	public static Specification<Event> titleContains(String q) {
		if (q == null || q.isBlank()) {
			return Specification.unrestricted();
		}
		String pattern = "%" + q.trim().toLowerCase() + "%";
		return (root, query, builder) -> builder.like(builder.lower(root.get("title")), pattern);
	}
}
