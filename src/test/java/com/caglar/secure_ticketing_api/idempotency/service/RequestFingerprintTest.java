package com.caglar.secure_ticketing_api.idempotency.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;


class RequestFingerprintTest {

	private static final String METHOD = "POST";
	private static final String URI = "/api/events/7/reservations";

	private RequestFingerprint fingerprint;

	@BeforeEach
	void setUp() {
		fingerprint = new RequestFingerprint(JsonMapper.builder().build());
	}

	private String hash(String body) {
		return fingerprint.forRequest(METHOD, URI, body.getBytes(StandardCharsets.UTF_8));
	}

	// --- formatting must not matter -----------------------------------------

	@Test
	void whitespaceDoesNotChangeTheFingerprint() {
		assertThat(hash("{\"seats\":2}")).isEqualTo(hash("{ \"seats\" : 2 }"));
	}

	@Test
	void fieldOrderDoesNotChangeTheFingerprint() {
		assertThat(hash("{\"seats\":2,\"note\":\"x\"}"))
				.isEqualTo(hash("{\"note\":\"x\",\"seats\":2}"));
	}

	@Test
	void nestedFieldOrderDoesNotChangeTheFingerprint() {
		assertThat(hash("{\"a\":{\"x\":1,\"y\":2}}")).isEqualTo(hash("{\"a\":{\"y\":2,\"x\":1}}"));
	}

	// --- meaning must matter -------------------------------------------------

	@Test
	void aChangedValueChangesTheFingerprint() {
		assertThat(hash("{\"seats\":2}")).isNotEqualTo(hash("{\"seats\":3}"));
	}

	@Test
	void anExtraFieldChangesTheFingerprint() {
		assertThat(hash("{\"seats\":2}")).isNotEqualTo(hash("{\"seats\":2,\"note\":\"x\"}"));
	}

	/** Arrays are ordered, so reordering them is a different request. */
	@Test
	void arrayOrderChangesTheFingerprint() {
		assertThat(hash("{\"a\":[1,2]}")).isNotEqualTo(hash("{\"a\":[2,1]}"));
	}

	@Test
	void aDifferentPathChangesTheFingerprint() {
		String body = "{\"seats\":2}";
		assertThat(fingerprint.forRequest(METHOD, "/api/events/7/reservations", body.getBytes(StandardCharsets.UTF_8)))
				.isNotEqualTo(fingerprint.forRequest(METHOD, "/api/events/8/reservations",
						body.getBytes(StandardCharsets.UTF_8)));
	}

	// --- edges ---------------------------------------------------------------

	@Test
	void anEmptyBodyIsStable() {
		assertThat(hash("")).isEqualTo(hash(""));
	}

	/** A body that is not JSON still has to produce a usable digest. */
	@Test
	void malformedJsonFallsBackToTheRawBytes() {
		assertThat(hash("{not json")).isEqualTo(hash("{not json"));
		assertThat(hash("{not json")).isNotEqualTo(hash("{also not json"));
	}

	@Test
	void theDigestIsSha256Hex() {
		assertThat(hash("{\"seats\":2}")).hasSize(64).matches("[0-9a-f]{64}");
	}

	@Test
	void responseHashesTrackTheirBytes() {
		byte[] body = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
		assertThat(fingerprint.forResponse(body)).isEqualTo(fingerprint.forResponse(body));
		assertThat(fingerprint.forResponse(body))
				.isNotEqualTo(fingerprint.forResponse("{\"id\":2}".getBytes(StandardCharsets.UTF_8)));
	}
}
