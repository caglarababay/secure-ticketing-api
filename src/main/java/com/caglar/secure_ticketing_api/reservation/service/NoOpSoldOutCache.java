package com.caglar.secure_ticketing_api.reservation.service;


class NoOpSoldOutCache implements SoldOutCache {

	@Override
	public boolean isSoldOut(Long eventId) {
		return false;
	}

	@Override
	public void markSoldOut(Long eventId) {
		// Nothing to remember.
	}

	@Override
	public void clear(Long eventId) {
		// Nothing to forget.
	}
}
