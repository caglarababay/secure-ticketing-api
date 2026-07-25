package com.caglar.secure_ticketing_api.reservation.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.caglar.secure_ticketing_api.event.domain.EventCapacityChanged;


@Component
class SoldOutCacheInvalidator {

	private final SoldOutCache soldOutCache;

	SoldOutCacheInvalidator(SoldOutCache soldOutCache) {
		this.soldOutCache = soldOutCache;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCapacityChanged(EventCapacityChanged event) {
		soldOutCache.clear(event.eventId());
	}
}
