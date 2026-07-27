package com.caglar.secure_ticketing_api.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.caglar.secure_ticketing_api.audit.domain.AuditAction;
import com.caglar.secure_ticketing_api.audit.domain.AuditLog;
import com.caglar.secure_ticketing_api.audit.domain.AuditLogRepository;
import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;
import com.caglar.secure_ticketing_api.event.domain.Event;
import com.caglar.secure_ticketing_api.event.domain.EventRepository;


@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "seed.demo=true")
class DemoDataSeederTest {

	@Autowired
	private DemoDataSeeder seeder;

	@Autowired
	private UserRepository users;

	@Autowired
	private EventRepository events;

	@Autowired
	private AuditLogRepository auditLogs;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void allThreeRolesAreRepresented() {
		assertThat(roleOf("admin@demo.local")).containsExactly(Role.ADMIN);
		assertThat(roleOf("organizer@demo.local")).containsExactly(Role.ORGANIZER);
		assertThat(roleOf("customer@demo.local")).containsExactly(Role.CUSTOMER);
	}

	/** The passwords are printed in the README; what is stored must not be. */
	@Test
	void thePasswordsAreStoredHashed() {
		String hash = users.findByEmail("admin@demo.local").orElseThrow().getPasswordHash();

		assertThat(hash).isNotEqualTo("demo-admin-pw").startsWith("$2");
		assertThat(passwordEncoder.matches("demo-admin-pw", hash)).isTrue();
	}

	@Test
	void thereIsSomethingToReserveAgainst() {
		Event demo = events.findAll().stream()
				.filter(event -> "Demo Concert".equals(event.getTitle()))
				.findFirst()
				.orElseThrow();

		assertThat(demo.isPublished()).isTrue();
		assertThat(demo.getCapacity()).isEqualTo(100);
		assertThat(demo.getOwnerId())
				.isEqualTo(users.findByEmail("organizer@demo.local").orElseThrow().getId());
	}

	@Test
	void theAccountsAreInTheAuditTrail() {
		List<AuditLog> entries = auditLogs.findAll();

		assertThat(entries).extracting(AuditLog::getAction)
				.contains(AuditAction.ADMIN_BOOTSTRAPPED, AuditAction.REGISTERED);
	}

	// --- running it again ------------------------------------------------------------

	@Test
	void runningItAgainChangesNothing() {
		long usersBefore = users.count();
		long eventsBefore = events.count();
		String hashBefore = users.findByEmail("admin@demo.local").orElseThrow().getPasswordHash();

		seeder.run(new DefaultApplicationArguments());

		assertThat(users.count()).isEqualTo(usersBefore);
		assertThat(events.count()).isEqualTo(eventsBefore);
		assertThat(users.findByEmail("admin@demo.local").orElseThrow().getPasswordHash())
				.isEqualTo(hashBefore);
	}

	@Test
	void runningItAgainAddsNoAuditEntries() {
		long before = auditLogs.count();

		seeder.run(new DefaultApplicationArguments());

		assertThat(auditLogs.count()).isEqualTo(before);
	}

	private java.util.Set<Role> roleOf(String email) {
		return users.findByEmail(email).map(User::getRoles).orElseThrow();
	}
}
