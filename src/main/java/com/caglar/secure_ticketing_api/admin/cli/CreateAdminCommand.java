package com.caglar.secure_ticketing_api.admin.cli;

import java.io.Console;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import com.caglar.secure_ticketing_api.audit.domain.AuditAction;
import com.caglar.secure_ticketing_api.audit.domain.AuditResource;
import com.caglar.secure_ticketing_api.audit.service.AuditRecorder;
import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.service.AccountCreator;

/**
 * Opens the first admin account, from a shell.
 *
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="\
 *     --create-admin --email=admin@example.com --spring.main.web-application-type=none"
 */
@Component
class CreateAdminCommand implements ApplicationRunner {

	static final String TRIGGER = "create-admin";
	private static final String EMAIL = "email";
	private static final String PASSWORD = "password";
	private static final int MIN_PASSWORD_LENGTH = 8;

	private static final Logger log = LoggerFactory.getLogger(CreateAdminCommand.class);

	private final AccountCreator accounts;
	private final AuditRecorder audit;
	private final ApplicationContext context;

	CreateAdminCommand(AccountCreator accounts, AuditRecorder audit, ApplicationContext context) {
		this.accounts = accounts;
		this.audit = audit;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!args.containsOption(TRIGGER)) {
			return;
		}
		exitWith(createAdmin(args));
	}

	/**
	 * @return the process exit code — zero only if an account was opened
	 */
	int createAdmin(ApplicationArguments args) {
		String email = single(args, EMAIL);
		if (email == null) {
			return fail("--email is required, for example --email=admin@example.com");
		}
		if (accounts.exists(email)) {
			return fail("An account already exists for %s. Nothing was changed."
					.formatted(accounts.normalise(email)));
		}

		char[] password = readPassword(args);
		if (password == null) {
			return 1;
		}

		try {
			User admin = accounts.create(email, new String(password), EnumSet.of(Role.ADMIN));
			audit.recordFor(admin.getId(), AuditAction.ADMIN_BOOTSTRAPPED, AuditResource.USER,
					admin.getId());
			log.info("Created ADMIN account {} <{}>", admin.getId(), admin.getEmail());
			return 0;
		}
		finally {
			Arrays.fill(password, '\0');
		}
	}

	private char[] readPassword(ApplicationArguments args) {
		Console console = System.console();
		if (console == null) {
			String supplied = single(args, PASSWORD);
			if (supplied == null) {
				fail("No terminal available to prompt for a password. "
						+ "Pass --password=… instead, and clear it from your shell history.");
				return null;
			}
			log.warn("Password was passed as an argument; it is now in your shell history "
					+ "and in this process's command line.");
			return supplied.toCharArray();
		}

		char[] first = console.readPassword("Password: ");
		char[] again = console.readPassword("Repeat:   ");
		if (first == null || !Arrays.equals(first, again)) {
			Arrays.fill(again == null ? new char[0] : again, '\0');
			fail("The two passwords did not match.");
			return null;
		}
		Arrays.fill(again, '\0');

		if (first.length < MIN_PASSWORD_LENGTH) {
			Arrays.fill(first, '\0');
			fail("The password must be at least %d characters.".formatted(MIN_PASSWORD_LENGTH));
			return null;
		}
		return first;
	}

	private String single(ApplicationArguments args, String name) {
		List<String> values = args.getOptionValues(name);
		return values == null || values.isEmpty() || values.getFirst().isBlank()
				? null
				: values.getFirst();
	}

	private int fail(String message) {
		log.error(message);
		return 1;
	}

	private void exitWith(int code) {
		System.exit(SpringApplication.exit(context, () -> code));
	}
}
