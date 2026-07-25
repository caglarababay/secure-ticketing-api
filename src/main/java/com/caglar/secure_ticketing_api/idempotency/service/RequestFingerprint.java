package com.caglar.secure_ticketing_api.idempotency.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;


@Component
public class RequestFingerprint {

	private final ObjectMapper objectMapper;

	RequestFingerprint(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String forRequest(String method, String uri, byte[] body) {
		return sha256(method + "\n" + uri + "\n" + canonicalJson(body));
	}

	public String forResponse(byte[] body) {
		return sha256(new String(body, StandardCharsets.UTF_8));
	}

	private String canonicalJson(byte[] body) {
		if (body == null || body.length == 0) {
			return "";
		}
		try {
			return objectMapper.writeValueAsString(canonicalize(objectMapper.readValue(body, Object.class)));
		}
		catch (JacksonException ex) {
			return new String(body, StandardCharsets.UTF_8);
		}
	}

	private Object canonicalize(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> sorted = new TreeMap<>();
			map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonicalize(nested)));
			return sorted;
		}
		if (value instanceof List<?> list) {
			return list.stream().map(this::canonicalize).toList();
		}
		return value;
	}

	private String sha256(String input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by every JVM", ex);
		}
	}
}
