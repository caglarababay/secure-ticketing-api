package com.caglar.secure_ticketing_api.demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.caglar.secure_ticketing_api.audit.domain.AuditAction;
import com.caglar.secure_ticketing_api.audit.domain.AuditResource;
import com.caglar.secure_ticketing_api.audit.service.AuditRecorder;
import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.service.AccountCreator;
import com.caglar.secure_ticketing_api.event.domain.Event;
import com.caglar.secure_ticketing_api.event.domain.EventRepository;


@Component
@ConditionalOnProperty(name = "seed.demo", havingValue = "true")
class DemoDataSeeder implements ApplicationRunner {

	private static final String EVENT_TITLE = "Demo Concert";
	private static final int EVENT_CAPACITY = 100;

	private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

	private final AccountCreator accounts;
	private final EventRepository events;
	private final AuditRecorder audit;
	private final Clock clock;

	DemoDataSeeder(AccountCreator accounts, EventRepository events, AuditRecorder audit, Clock clock) {
		this.accounts = accounts;
		this.events = events;
		this.audit = audit;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		log.warn("Demo data is enabled. The passwords are in the README — never enable this "
				+ "outside a development environment.");

		seed("admin@demo.local", "demo-admin-pw", EnumSet.of(Role.ADMIN),
				AuditAction.ADMIN_BOOTSTRAPPED);
		User organizer = seed("organizer@demo.local", "demo-organizer-pw",
				EnumSet.of(Role.ORGANIZER), AuditAction.REGISTERED);
		seed("customer@demo.local", "demo-customer-pw", EnumSet.of(Role.CUSTOMER),
				AuditAction.REGISTERED);

		if (organizer != null) {
			seedPublishedEvent(organizer);
		}
	}

	private User seed(String email, String password, Set<Role> roles, AuditAction action) {
		if (accounts.exists(email)) {
			log.info("Demo account {} already exists; left untouched", email);
			return null;
		}

		User created = accounts.create(email, password, roles);
		audit.recordFor(created.getId(), action, AuditResource.USER, created.getId());
		log.info("Created demo account {} <{}> with {}", created.getId(), created.getEmail(), roles);
		return created;
	}

	private void seedPublishedEvent(User organizer) {
		Instant starts = Instant.now(clock).plus(Duration.ofDays(30));

		Event event = new Event(organizer.getId(), EVENT_TITLE, "Demo Arena",
				starts, starts.plus(Duration.ofHours(3)), EVENT_CAPACITY);
		event.publish();

		log.info("Created published demo event {} with {} seats",
				events.save(event).getId(), EVENT_CAPACITY);
	}
}
