package com.caglar.secure_ticketing_api.auth.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import com.caglar.secure_ticketing_api.auth.domain.Role;
import com.caglar.secure_ticketing_api.auth.domain.User;
import com.caglar.secure_ticketing_api.common.error.ApiException;
import com.caglar.secure_ticketing_api.common.error.ErrorCode;


@Service
public class JwtService {

	static final String CLAIM_TYPE = "type";
	static final String CLAIM_ROLES = "roles";
	static final String CLAIM_EMAIL = "email";
	static final String TYPE_ACCESS = "access";
	static final String TYPE_REFRESH = "refresh";

	private final JwtEncoder encoder;
	private final JwtDecoder decoder;
	private final JwtProperties properties;
	private final Clock clock;

	JwtService(JwtProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;

		SecretKey key = new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		this.encoder = NimbusJwtEncoder.withSecretKey(key).build();

		NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(key)
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		
		JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
		timestampValidator.setClock(clock);
		nimbusDecoder.setJwtValidator(timestampValidator);
		this.decoder = nimbusDecoder;
	}

	public String createAccessToken(User user) {
		return create(user, TYPE_ACCESS, properties.accessTokenTtl());
	}

	public String createRefreshToken(User user) {
		return create(user, TYPE_REFRESH, properties.refreshTokenTtl());
	}

	public Jwt decode(String token, boolean requireAccessToken) {
		Jwt jwt;
		try {
			jwt = decoder.decode(token);
		}
		catch (JwtException ex) {
			throw new ApiException(ErrorCode.INVALID_TOKEN, "Token is invalid or expired.", ex);
		}

		String expectedType = requireAccessToken ? TYPE_ACCESS : TYPE_REFRESH;
		if (!expectedType.equals(jwt.getClaimAsString(CLAIM_TYPE))) {
			throw new ApiException(ErrorCode.INVALID_TOKEN, "Token is invalid or expired.");
		}
		return jwt;
	}

	public List<String> rolesOf(Jwt jwt) {
		List<String> roles = jwt.getClaimAsStringList(CLAIM_ROLES);
		return roles != null ? roles : List.of();
	}

	public long accessTokenTtlSeconds() {
		return properties.accessTokenTtl().toSeconds();
	}

	private String create(User user, String type, java.time.Duration ttl) {
		Instant now = Instant.now(clock);
		List<String> roles = user.getRoles().stream().map(Role::name).toList();

		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(String.valueOf(user.getId()))
				.issuedAt(now)
				.expiresAt(now.plus(ttl))
				.claim(CLAIM_TYPE, type)
				.claim(CLAIM_EMAIL, user.getEmail())
				.claim(CLAIM_ROLES, roles)
				.build();

		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}
}
