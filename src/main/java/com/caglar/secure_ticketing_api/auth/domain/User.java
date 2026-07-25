package com.caglar.secure_ticketing_api.auth.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
	@Column(name = "role", nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	private Set<Role> roles = EnumSet.noneOf(Role.class);

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	protected User() {
		// Required by JPA.
	}

	public User(String email, String passwordHash, Set<Role> roles, Instant createdAt) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.roles = EnumSet.noneOf(Role.class);
		this.roles.addAll(roles);
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Set<Role> getRoles() {
		return Set.copyOf(roles);
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLastLoginAt() {
		return lastLoginAt;
	}

	public void recordLogin(Instant when) {
		this.lastLoginAt = when;
	}
}
