package com.caglar.secure_ticketing_api.audit.domain;

import java.util.List;

import org.springframework.data.repository.Repository;

/**
 * Insert and read, and nothing else.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

	AuditLog save(AuditLog entry);

	List<AuditLog> findAll();

	long count();

	List<AuditLog> findByActionOrderByCreatedAtDesc(AuditAction action);

	List<AuditLog> findByActorIdOrderByCreatedAtDesc(Long actorId);
}
