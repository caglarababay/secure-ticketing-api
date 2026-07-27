package com.caglar.secure_ticketing_api.admin.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.caglar.secure_ticketing_api.audit.AuditLogTestSupport;
import com.caglar.secure_ticketing_api.audit.domain.AuditAction;
import com.caglar.secure_ticketing_api.audit.domain.AuditLog;
import com.caglar.secure_ticketing_api.audit.domain.AuditLogRepository;
import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;


@SpringBootTest
@ActiveProfiles("test")
class CreateAdminCommandTest {

	private static final String PASSWORD = "bootstrap-secret";

	@Autowired
	private CreateAdminCommand command;

	@Autowired
	private UserRepository users;

	@Autowired
	private AuditLogRepository auditLogs;

	@Autowired
	private AuditLogTestSupport auditTrail;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void setUp() {
		auditTrail.clear();
		users.deleteAll();
	}

	@Test
	void anOrdinaryStartCreatesNothing() {
		command.run(new DefaultApplicationArguments());

		assertThat(users.count()).isZero();
	}

	@Test
	void otherArgumentsAreIgnored() {
		command.run(new DefaultApplicationArguments("--server.port=9999"));

		assertThat(users.count()).isZero();
	}

	// --- creating ----------------------------------------------------------------

	@Test
	void anAdminAccountIsOpened() {
		int exitCode = createAdmin("--email=admin@example.com", "--password=" + PASSWORD);

		assertThat(exitCode).isZero();
		User admin = users.findByEmail("admin@example.com").orElseThrow();
		assertThat(admin.getRoles()).containsExactly(Role.ADMIN);
	}

	@Test
	void thePasswordIsStoredHashed() {
		createAdmin("--email=admin@example.com", "--password=" + PASSWORD);

		String stored = users.findByEmail("admin@example.com").orElseThrow().getPasswordHash();
		assertThat(stored).isNotEqualTo(PASSWORD).startsWith("$2");
		assertThat(passwordEncoder.matches(PASSWORD, stored)).isTrue();
	}

	@Test
	void theAddressIsNormalised() {
		createAdmin("--email=  ADMIN@Example.COM  ", "--password=" + PASSWORD);

		assertThat(users.findByEmail("admin@example.com")).isPresent();
	}

	@Test
	void theBootstrapIsRecordedInTheAuditTrail() {
		createAdmin("--email=admin@example.com", "--password=" + PASSWORD);

		Long adminId = users.findByEmail("admin@example.com").orElseThrow().getId();
		List<AuditLog> entries = auditLogs.findAll();
		assertThat(entries).hasSize(1);
		assertThat(entries.getFirst().getAction()).isEqualTo(AuditAction.ADMIN_BOOTSTRAPPED);
		assertThat(entries.getFirst().getActorId())
				.as("the new admin is the only party involved")
				.isEqualTo(adminId);

	}

	// --- refusing ------------------------------------------------------------------

	@Test
	void anExistingAddressIsLeftAlone() {
		users.save(new User("taken@example.com", passwordEncoder.encode("original"),
				java.util.EnumSet.of(Role.CUSTOMER), java.time.Instant.now()));

		int exitCode = createAdmin("--email=taken@example.com", "--password=" + PASSWORD);

		assertThat(exitCode).isNotZero();
		User untouched = users.findByEmail("taken@example.com").orElseThrow();
		assertThat(untouched.getRoles()).containsExactly(Role.CUSTOMER);
		assertThat(passwordEncoder.matches("original", untouched.getPasswordHash())).isTrue();
	}

	@Test
	void anExistingAddressIsMatchedAfterNormalising() {
		users.save(new User("taken@example.com", passwordEncoder.encode("original"),
				java.util.EnumSet.of(Role.CUSTOMER), java.time.Instant.now()));

		assertThat(createAdmin("--email=TAKEN@Example.com", "--password=" + PASSWORD)).isNotZero();
		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	void aMissingAddressIsAnError() {
		int exitCode = createAdmin("--password=" + PASSWORD);

		assertThat(exitCode).isNotZero();
		assertThat(users.count()).isZero();
	}

	@Test
	void aBlankAddressIsAnError() {
		assertThat(createAdmin("--email=", "--password=" + PASSWORD)).isNotZero();
		assertThat(users.count()).isZero();
	}

	@Test
	void aMissingPasswordIsAnErrorWhenThereIsNoTerminal() {
		int exitCode = createAdmin("--email=admin@example.com");

		assertThat(exitCode).isNotZero();
		assertThat(users.count()).isZero();
	}

	@Test
	void aFailedRunLeavesNoAuditEntry() {
		createAdmin("--email=admin@example.com");

		assertThat(auditLogs.count()).isZero();
	}

	private int createAdmin(String... arguments) {
		String[] all = new String[arguments.length + 1];
		all[0] = "--" + CreateAdminCommand.TRIGGER;
		System.arraycopy(arguments, 0, all, 1, arguments.length);
		return command.createAdmin(new DefaultApplicationArguments(all));
	}
}
