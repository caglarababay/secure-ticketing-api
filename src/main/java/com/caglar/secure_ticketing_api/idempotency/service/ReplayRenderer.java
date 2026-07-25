package com.caglar.secure_ticketing_api.idempotency.service;


public interface ReplayRenderer {

	String endpoint();

	Long extractResourceId(byte[] responseBody);

	RenderedResponse render(Long resourceId);

	record RenderedResponse(int status, String body) {
	}
}
