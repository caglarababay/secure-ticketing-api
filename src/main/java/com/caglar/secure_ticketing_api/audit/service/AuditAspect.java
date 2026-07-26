package com.caglar.secure_ticketing_api.audit.service;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Aspect
@Component
class AuditAspect {

	private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

	private final AuditRecorder recorder;

	AuditAspect(AuditRecorder recorder) {
		this.recorder = recorder;
	}

	@Around("@annotation(audited)")
	Object record(ProceedingJoinPoint call, Audited audited) throws Throwable {
		Object result = call.proceed();

		recorder.record(audited.action(), audited.resource(), resourceIdOf(result, call));
		return result;
	}

	private Long resourceIdOf(Object result, ProceedingJoinPoint call) {
		if (result instanceof AuditableResource resource) {
			return resource.auditId();
		}

		log.warn("{} is @Audited but returned {}, which is not an AuditableResource; "
				+ "the entry will have no resource id",
				call.getSignature().toShortString(),
				result == null ? "null" : result.getClass().getSimpleName());
		return null;
	}
}
