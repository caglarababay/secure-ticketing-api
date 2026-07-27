package com.caglar.secure_ticketing_api.admin.service;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.caglar.secure_ticketing_api.audit.domain.AuditAction;
import com.caglar.secure_ticketing_api.audit.domain.AuditResource;
import com.caglar.secure_ticketing_api.audit.service.AuditRecorder;
import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.auth.service.AccountCreator;


@Service
public class AdminUserService {

	private final AccountCreator accounts;
	private final AuditRecorder audit;

	AdminUserService(AccountCreator accounts, AuditRecorder audit) {
		this.accounts = accounts;
		this.audit = audit;
	}

	@Transactional
	public User create(String email, String password, Set<Role> roles) {
		User created = accounts.create(email, password, roles);

		audit.record(AuditAction.ADMIN_CREATED_USER, AuditResource.USER, created.getId());
		return created;
	}
}
