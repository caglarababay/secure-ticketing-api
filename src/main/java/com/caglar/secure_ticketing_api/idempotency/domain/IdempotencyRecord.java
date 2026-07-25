package com.caglar.secure_ticketing_api.idempotency.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;


@Entity
@Table(name = "idempotency_keys", 
uniqueConstraints = @UniqueConstraint(name = "uq_idempotency_scope",
				columnNames = { "user_id", "idempotency_key", "endpoint" }))
public class IdempotencyRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "idempotency_key", nullable = false, length = 100)
	private String key;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(nullable = false, length = 200)
	private String endpoint;

	@Column(name = "request_hash", nullable = false, length = 64)
	private String requestHash;

	@Column(nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	private IdempotencyStatus status;

	@Column(name = "resource_id")
	private Long resourceId;

	@Column(name = "response_hash", length = 64)
	private String responseHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "locked_until", nullable = false)
	private Instant lockedUntil;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	protected IdempotencyRecord() {
		// Required by JPA.
	}

	public IdempotencyRecord(String key, Long userId, String endpoint, String requestHash,
			Instant createdAt, Instant lockedUntil, Instant expiresAt) {

		this.key = key;
		this.userId = userId;
		this.endpoint = endpoint;
		this.requestHash = requestHash;
		this.createdAt = createdAt;
		this.lockedUntil = lockedUntil;
		this.expiresAt = expiresAt;
		this.status = IdempotencyStatus.IN_PROGRESS;
	}

	public void markCompleted(Long resourceId, String responseHash) {
		this.resourceId = resourceId;
		this.responseHash = responseHash;
		this.status = IdempotencyStatus.COMPLETED;
	}

	public void renewLease(Instant lockedUntil) {
		this.lockedUntil = lockedUntil;
	}

	public boolean isExpired(Instant now) {
		return now.isAfter(this.expiresAt);
	}

	public boolean isLeaseExpired(Instant now) {
		return now.isAfter(this.lockedUntil);
	}

	public boolean matchesRequest(String candidateHash) {
		return this.requestHash.equals(candidateHash);
	}

	public boolean canReplay() {
		return this.status.canReplay();
	}

	public Long getId() {
		return id;
	}

	public String getKey() {
		return key;
	}

	public Long getUserId() {
		return userId;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public IdempotencyStatus getStatus() {
		return status;
	}

	public Long getResourceId() {
		return resourceId;
	}

	public String getResponseHash() {
		return responseHash;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLockedUntil() {
		return lockedUntil;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}
}
