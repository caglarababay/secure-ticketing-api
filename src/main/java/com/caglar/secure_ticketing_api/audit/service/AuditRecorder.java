package com.caglar.secure_ticketing_api.audit.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.caglar.secure_ticketing_api.audit.domain.AuditAction;
import com.caglar.secure_ticketing_api.audit.domain.AuditLog;
import com.caglar.secure_ticketing_api.audit.domain.AuditLogRepository;
import com.caglar.secure_ticketing_api.audit.domain.AuditResource;
import com.caglar.secure_ticketing_api.common.audit.RequestMetadata;
import com.caglar.secure_ticketing_api.common.security.AuthenticatedActor;


@Service
public class AuditRecorder {

	private final AuditLogRepository auditLogs;
	private final AuthenticatedActor actor;
	private final RequestMetadata requestMetadata;
	private final Clock clock;
	private final TransactionTemplate ownTransaction;

	AuditRecorder(AuditLogRepository auditLogs, AuthenticatedActor actor,
			RequestMetadata requestMetadata, Clock clock,
			PlatformTransactionManager transactionManager) {

		this.auditLogs = auditLogs;
		this.actor = actor;
		this.requestMetadata = requestMetadata;
		this.clock = clock;
		this.ownTransaction = new TransactionTemplate(transactionManager);
		this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Transactional
	public void record(AuditAction action, AuditResource resourceType, Long resourceId) {
		recordFor(actor.currentId(), action, resourceType, resourceId);
	}

	@Transactional
	public void recordFor(Long actorId, AuditAction action, AuditResource resourceType,
			Long resourceId) {

		auditLogs.save(newEntry(actorId, action, resourceType, resourceId));
	}

	public void recordFailure(Long actorId, AuditAction action, AuditResource resourceType,
			Long resourceId) {

		ownTransaction.executeWithoutResult(status ->
				auditLogs.save(newEntry(actorId, action, resourceType, resourceId)));
	}

	private AuditLog newEntry(Long actorId, AuditAction action, AuditResource resourceType,
			Long resourceId) {

		return new AuditLog(actorId, action, resourceType, resourceId,
				requestMetadata.clientAddress(), requestMetadata.userAgent(), Instant.now(clock));
	}
}
