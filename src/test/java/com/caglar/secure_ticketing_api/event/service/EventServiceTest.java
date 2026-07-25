package com.caglar.secure_ticketing_api.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.caglar.secure_ticketing_api.common.error.ApiException;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;
import com.caglar.secure_ticketing_api.event.api.CreateEventRequest;
import com.caglar.secure_ticketing_api.event.api.UpdateEventRequest;
import com.caglar.secure_ticketing_api.event.domain.Event;
import com.caglar.secure_ticketing_api.event.domain.EventRepository;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

	private static final Long OWNER_ID = 7L;
	private static final Long OTHER_USER_ID = 99L;
	private static final Instant STARTS = Instant.parse("2027-07-01T18:00:00Z");
	private static final Instant ENDS = STARTS.plus(Duration.ofHours(4));

	@Mock
	private EventRepository events;

	private EventService service() {
		return new EventService(events);
	}

	private Event storedEvent() {
		Event event = new Event(OWNER_ID, "Concert", "Arena", STARTS, ENDS, 500);
		ReflectionTestUtils.setField(event, "id", 1L);
		return event;
	}

	private UpdateEventRequest updateRequest() {
		return new UpdateEventRequest("Renamed", "Hall", STARTS, ENDS, 600);
	}

	// --- create -------------------------------------------------------------

	@Test
	void createProducesAnUnpublishedEventOwnedByTheCaller() {
		when(events.save(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service().create(new CreateEventRequest("Concert", "Arena", STARTS, ENDS, 500), OWNER_ID);

		ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
		verify(events).save(saved.capture());
		assertThat(saved.getValue().isPublished()).isFalse();
		assertThat(saved.getValue().getOwnerId()).isEqualTo(OWNER_ID);
		assertThat(saved.getValue().getCapacity()).isEqualTo(500);
	}

	// --- ownership ----------------------------------------------------------

	@Test
	void ownerCanUpdateTheirOwnEvent() {
		when(events.findById(1L)).thenReturn(Optional.of(storedEvent()));

		Event updated = service().update(1L, updateRequest(), OWNER_ID, false);

		assertThat(updated.getTitle()).isEqualTo("Renamed");
		assertThat(updated.getCapacity()).isEqualTo(600);
	}

	@Test
	void anotherOrganizerCannotUpdateSomeoneElsesEvent() {
		when(events.findById(1L)).thenReturn(Optional.of(storedEvent()));

		assertThatThrownBy(() -> service().update(1L, updateRequest(), OTHER_USER_ID, false))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	/** An admin is not the owner, but is allowed through anyway. */
	@Test
	void adminCanUpdateAnyEvent() {
		when(events.findById(1L)).thenReturn(Optional.of(storedEvent()));

		Event updated = service().update(1L, updateRequest(), OTHER_USER_ID, true);

		assertThat(updated.getTitle()).isEqualTo("Renamed");
	}

	@Test
	void anotherOrganizerCannotPublishSomeoneElsesEvent() {
		when(events.findById(1L)).thenReturn(Optional.of(storedEvent()));

		assertThatThrownBy(() -> service().publish(1L, OTHER_USER_ID, false))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	@Test
	void missingEventIsReportedAsNotFound() {
		when(events.findById(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service().update(42L, updateRequest(), OWNER_ID, false))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}

	// --- publish ------------------------------------------------------------

	@Test
	void publishFlipsTheDraftToPublished() {
		when(events.findById(1L)).thenReturn(Optional.of(storedEvent()));

		assertThat(service().publish(1L, OWNER_ID, false).isPublished()).isTrue();
	}

	@Test
	void publishingTwiceIsAConflict() {
		Event event = storedEvent();
		event.publish();
		when(events.findById(1L)).thenReturn(Optional.of(event));

		assertThatThrownBy(() -> service().publish(1L, OWNER_ID, false))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.EVENT_ALREADY_PUBLISHED);
	}
}
