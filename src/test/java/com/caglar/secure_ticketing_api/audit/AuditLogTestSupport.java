package com.caglar.secure_ticketing_api.audit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;


@Component
public class AuditLogTestSupport {

	@PersistenceContext
	private EntityManager entityManager;

	private TransactionTemplate transactionTemplate;

	@Autowired
	void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	public void clear() {
		transactionTemplate.executeWithoutResult(status ->
				entityManager.createQuery("delete from AuditLog").executeUpdate());
	}
}
