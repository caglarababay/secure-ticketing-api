package com.caglar.secure_ticketing_api.audit.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;


@Entity
@Table(name = "audit_logs")
public class AuditLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "actor_id")
	private Long actorId;

	@Column(nullable = false, length = 40)
	@Enumerated(EnumType.STRING)
	private AuditAction action;

	@Column(name = "resource_type", nullable = false, length = 40)
	@Enumerated(EnumType.STRING)
	private AuditResource resourceType;

	@Column(name = "resource_id")
	private Long resourceId;

	@Column(length = 45)
	private String ip;

	@Column(name = "user_agent", length = 255)
	private String userAgent;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AuditLog() {
		// Required by JPA.
	}

	public AuditLog(Long actorId, AuditAction action, AuditResource resourceType, Long resourceId,
			String ip, String userAgent, Instant createdAt) {

		this.actorId = actorId;
		this.action = action;
		this.resourceType = resourceType;
		this.resourceId = resourceId;
		this.ip = ip;
		this.userAgent = userAgent;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getActorId() {
		return actorId;
	}

	public AuditAction getAction() {
		return action;
	}

	public AuditResource getResourceType() {
		return resourceType;
	}

	public Long getResourceId() {
		return resourceId;
	}

	public String getIp() {
		return ip;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
