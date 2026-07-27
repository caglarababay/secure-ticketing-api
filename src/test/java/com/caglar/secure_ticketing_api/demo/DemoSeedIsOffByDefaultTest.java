package com.caglar.secure_ticketing_api.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import com.caglar.secure_ticketing_api.SecureTicketingApiApplication;
import com.caglar.secure_ticketing_api.auth.domain.UserRepository;


@SpringBootTest
@ActiveProfiles("test")
class DemoSeedIsOffByDefaultTest {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private Environment environment;

	@Test
	void theSeederIsNotEvenACandidate() {
		assertThat(context.getBeanNamesForType(DemoDataSeeder.class)).isEmpty();
	}

	/** Guards the default itself. */
	@Test
	void theShippedDefaultIsOff() {
		assertThat(environment.getProperty("seed.demo")).isEqualTo("false");
	}

	@Test
	void aFreshApplicationCreatesNoAccounts() {
		SpringApplication app = new SpringApplication(SecureTicketingApiApplication.class);
		app.setWebApplicationType(WebApplicationType.NONE);

		try (ConfigurableApplicationContext isolated = app.run("--spring.profiles.active=test",
				"--spring.datasource.url=jdbc:h2:mem:seed-off-probe")) {

			assertThat(isolated.getBean(UserRepository.class).count()).isZero();
		}
	}
}
