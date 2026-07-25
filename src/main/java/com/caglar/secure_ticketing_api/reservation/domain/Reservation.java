package com.caglar.secure_ticketing_api.reservation.domain;

import java.time.Instant;

import com.caglar.secure_ticketing_api.common.error.ApiException;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "reservations")
public class Reservation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id", nullable = false)
	private Long eventId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	private ReservationStatus status;

	@Column(nullable = false)
	private int seats;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Version
	private Long version;

	protected Reservation() {
		// Required by JPA.
	}

	public Reservation(Long eventId, Long userId, int seats, Instant createdAt, Instant expiresAt) {
		this.eventId = eventId;
		this.userId = userId;
		this.seats = seats;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.status = ReservationStatus.PENDING;
	}

	public void confirm() {
		transitionTo(ReservationStatus.CONFIRMED);
	}

	public void cancel() {
		transitionTo(ReservationStatus.CANCELLED);
	}

	private void transitionTo(ReservationStatus target) {
		if (!this.status.canTransitionTo(target)) {
			throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
					"Cannot move a %s reservation to %s.".formatted(this.status, target));
		}
		this.status = target;
		this.expiresAt = null;
	}

	public boolean holdsSeats() {
		return this.status.holdsSeats();
	}

	public boolean isOwnedBy(Long candidateUserId) {
		return this.userId.equals(candidateUserId);
	}

	public Long getId() {
		return id;
	}

	public Long getEventId() {
		return eventId;
	}

	public Long getUserId() {
		return userId;
	}

	public ReservationStatus getStatus() {
		return status;
	}

	public int getSeats() {
		return seats;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Long getVersion() {
		return version;
	}
}
