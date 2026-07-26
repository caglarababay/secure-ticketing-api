package com.caglar.secure_ticketing_api.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;


class AuditLogImmutabilityTest {

	@Test
	void theEntityExposesNoWayToChangeIt() {
		assertThat(publicMethods())
				.extracting(Method::getName)
				.allSatisfy(name -> assertThat(name)
						.as("%s looks like a mutator", name)
						.doesNotStartWith("set")
						.doesNotStartWith("update")
						.doesNotStartWith("mark")
						.doesNotStartWith("clear"));
	}

	@Test
	void everyPublicMethodIsAGetter() {
		assertThat(publicMethods())
				.allSatisfy(method -> assertThat(method.getName())
						.as("%s is neither a getter nor inherited", method.getName())
						.startsWith("get"));
	}

	@Test
	void noFieldIsPubliclyWritable() {
		assertThat(AuditLog.class.getDeclaredFields())
				.allSatisfy(field -> assertThat(Modifier.isPublic(field.getModifiers()))
						.as("field %s", field.getName())
						.isFalse());
	}

	@Test
	void theWholeRecordIsSetThroughTheConstructor() {
		Instant now = Instant.parse("2026-07-26T10:00:00Z");
		AuditLog entry = new AuditLog(7L, AuditAction.LOGIN_FAILED, AuditResource.USER, 7L,
				"203.0.113.1", "curl/8.4.0", now);

		assertThat(entry.getActorId()).isEqualTo(7L);
		assertThat(entry.getAction()).isEqualTo(AuditAction.LOGIN_FAILED);
		assertThat(entry.getResourceType()).isEqualTo(AuditResource.USER);
		assertThat(entry.getResourceId()).isEqualTo(7L);
		assertThat(entry.getIp()).isEqualTo("203.0.113.1");
		assertThat(entry.getUserAgent()).isEqualTo("curl/8.4.0");
		assertThat(entry.getCreatedAt()).isEqualTo(now);
	}

	@Test
	void anActorlessRecordIsRepresentable() {
		AuditLog entry = new AuditLog(null, AuditAction.LOGIN_FAILED, AuditResource.USER, null,
				null, null, Instant.now());

		assertThat(entry.getActorId()).isNull();
		assertThat(entry.getResourceId()).isNull();
	}

	@Test
	void theRepositoryOffersNoWayToRemoveOrRewriteARecord() {
		assertThat(AuditLogRepository.class.getMethods())
				.extracting(Method::getName)
				.allSatisfy(name -> assertThat(name)
						.as("%s would let the trail be edited", name)
						.doesNotStartWith("delete")
						.doesNotStartWith("remove")
						.isNotEqualTo("saveAll")
						.isNotEqualTo("saveAllAndFlush"));
	}

	@Test
	void theOnlyWriteIsASingleInsert() {
		assertThat(AuditLogRepository.class.getMethods())
				.filteredOn(method -> method.getName().startsWith("save"))
				.extracting(Method::getName)
				.containsExactly("save");
	}

	private java.util.List<Method> publicMethods() {
		return Arrays.stream(AuditLog.class.getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers()))
				.filter(method -> !method.isSynthetic())
				.toList();
	}
}
