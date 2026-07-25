package com.caglar.secure_ticketing_api.reservation.service;


public interface SoldOutCache {

	boolean isSoldOut(Long eventId);

	void markSoldOut(Long eventId);

	void clear(Long eventId);
}
