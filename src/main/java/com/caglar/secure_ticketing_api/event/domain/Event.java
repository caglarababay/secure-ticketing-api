package com.caglar.secure_ticketing_api.event.domain;

import java.time.Instant;

import com.caglar.secure_ticketing_api.common.error.ApiException;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "events")
public class Event {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "owner_id", nullable = false)
	private Long ownerId;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, length = 200)
	private String venue;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Column(nullable = false)
	private int capacity;

	@Column(name = "reserved_seats", nullable = false)
	private int reservedSeats;

	@Column(nullable = false)
	private boolean published;

	@Version
	private Long version;

	protected Event() {
		// Required by JPA.
	}

	public Event(Long ownerId, String title, String venue, Instant startsAt, Instant endsAt, int capacity) {
		this.ownerId = ownerId;
		this.title = title;
		this.venue = venue;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.capacity = capacity;
		this.published = false;
	}

	public void updateDetails(String title, String venue, Instant startsAt, Instant endsAt, int capacity) {
		// Capacity cannot drop below what is already sold. 
		if (capacity < this.reservedSeats) {
			throw new ApiException(ErrorCode.CAPACITY_BELOW_RESERVED,
					"Capacity cannot be reduced to %d; %d seat(s) are already reserved."
							.formatted(capacity, this.reservedSeats));
		}

		this.title = title;
		this.venue = venue;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.capacity = capacity;
	}

	public boolean hasDifferentCapacityThan(int otherCapacity) {
		return this.capacity != otherCapacity;
	}

	public void publish() {
		if (this.published) {
			throw new ApiException(ErrorCode.EVENT_ALREADY_PUBLISHED, "This event is already published.");
		}
		this.published = true;
	}

	public boolean isOwnedBy(Long userId) {
		return this.ownerId.equals(userId);
	}

	public Long getId() {
		return id;
	}

	public Long getOwnerId() {
		return ownerId;
	}

	public String getTitle() {
		return title;
	}

	public String getVenue() {
		return venue;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getEndsAt() {
		return endsAt;
	}

	public int getCapacity() {
		return capacity;
	}

	public int getReservedSeats() {
		return reservedSeats;
	}

	public int getAvailableSeats() {
		return capacity - reservedSeats;
	}

	public boolean isPublished() {
		return published;
	}

	public Long getVersion() {
		return version;
	}
}
