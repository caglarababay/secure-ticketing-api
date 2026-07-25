package com.caglar.secure_ticketing_api.event.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;


@SpringBootTest
@ActiveProfiles("test")
class EventOptimisticLockingTest {

	private static final Instant STARTS = Instant.parse("2027-07-01T18:00:00Z");
	private static final Instant ENDS = STARTS.plus(Duration.ofHours(4));

	@Autowired
	private EventRepository events;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private Long eventId;

	@BeforeEach
	void setUp() {
		events.deleteAll();
		eventId = events.save(new Event(1L, "Concert", "Arena", STARTS, ENDS, 500)).getId();
	}

	@Test
	void versionStartsAtZeroAndIncrementsOnUpdate() {
		assertThat(events.findById(eventId).orElseThrow().getVersion()).isZero();

		transactionTemplate.executeWithoutResult(status -> {
			Event event = events.findById(eventId).orElseThrow();
			event.updateDetails("Renamed", "Hall", STARTS, ENDS, 600);
		});

		assertThat(events.findById(eventId).orElseThrow().getVersion()).isEqualTo(1L);
	}

	@Test
	void secondWriterOnAStaleVersionIsRejected() {
		Event firstReader = events.findById(eventId).orElseThrow();
		Event secondReader = events.findById(eventId).orElseThrow();
		assertThat(firstReader.getVersion()).isEqualTo(secondReader.getVersion());

		firstReader.updateDetails("Won", "Hall A", STARTS, ENDS, 600);
		events.saveAndFlush(firstReader);

		secondReader.updateDetails("Lost", "Hall B", STARTS, ENDS, 700);

		assertThatThrownBy(() -> events.saveAndFlush(secondReader))
				.isInstanceOf(ObjectOptimisticLockingFailureException.class);

		assertThat(events.findById(eventId).orElseThrow().getTitle()).isEqualTo("Won");
	}

	@Test
	void publishAlsoBumpsTheVersion() {
		transactionTemplate.executeWithoutResult(status -> events.findById(eventId).orElseThrow().publish());

		Event reloaded = events.findById(eventId).orElseThrow();
		assertThat(reloaded.isPublished()).isTrue();
		assertThat(reloaded.getVersion()).isEqualTo(1L);
	}
}
