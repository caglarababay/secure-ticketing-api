package com.caglar.secure_ticketing_api.idempotency.service;


public sealed interface IdempotencyClaim {

	record Claimed(Long recordId) implements IdempotencyClaim {
	}

	record Replay(Long resourceId, String responseHash) implements IdempotencyClaim {
	}

	record Mismatch() implements IdempotencyClaim {
	}

	record InProgress() implements IdempotencyClaim {
	}
}
