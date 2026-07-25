package com.caglar.secure_ticketing_api.reservation.api;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.caglar.secure_ticketing_api.idempotency.service.ReplayRenderer;
import com.caglar.secure_ticketing_api.reservation.domain.ReservationRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


@Component
class ReservationReplayRenderer implements ReplayRenderer {

	static final String ENDPOINT = "POST /api/events/{eventId}/reservations";

	private final ReservationRepository reservations;
	private final ObjectMapper objectMapper;

	ReservationReplayRenderer(ReservationRepository reservations, ObjectMapper objectMapper) {
		this.reservations = reservations;
		this.objectMapper = objectMapper;
	}

	@Override
	public String endpoint() {
		return ENDPOINT;
	}

	@Override
	public Long extractResourceId(byte[] responseBody) {
		try {
			JsonNode id = objectMapper.readTree(responseBody).get("id");
			return id != null && id.isNumber() ? id.asLong() : null;
		}
		catch (JacksonException ex) {
			return null;
		}
	}

	@Override
	public RenderedResponse render(Long resourceId) {
		return reservations.findById(resourceId)
				.map(reservation -> new RenderedResponse(HttpStatus.CREATED.value(),
						objectMapper.writeValueAsString(ReservationResponse.from(reservation))))
				.orElse(null);
	}
}
