package com.caglar.secure_ticketing_api.idempotency.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

	Optional<IdempotencyRecord> findByUserIdAndKeyAndEndpoint(Long userId, String key, String endpoint);

	@Query("""
			select r from IdempotencyRecord r
			where r.expiresAt < :now
			order by r.expiresAt
			""")
	List<IdempotencyRecord> findExpired(@Param("now") Instant now, Pageable pageable);
}
