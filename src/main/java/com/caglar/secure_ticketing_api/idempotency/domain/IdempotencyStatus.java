package com.caglar.secure_ticketing_api.idempotency.domain;


public enum IdempotencyStatus {

	IN_PROGRESS,
	COMPLETED;

	public boolean canReplay() {
		return this == COMPLETED;
	}

	public boolean isTerminal() {
		return this == COMPLETED;
	}
}
