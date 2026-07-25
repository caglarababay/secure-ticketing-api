package com.caglar.secure_ticketing_api.reservation.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	Page<Reservation> findAllByUserId(Long userId, Pageable pageable);

	@Query("""
			select coalesce(sum(r.seats), 0) from Reservation r
			where r.eventId = :eventId and r.status <> 'CANCELLED'
			""")
	int sumActiveSeats(@Param("eventId") Long eventId);

	@Query("""
			select r from Reservation r
			where r.status = 'PENDING' and r.expiresAt < :now
			order by r.expiresAt
			""")
	List<Reservation> findExpiredHolds(@Param("now") Instant now, Pageable pageable);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update Reservation r set r.status = 'CANCELLED', r.expiresAt = null
			where r.id = :id and r.status = 'PENDING'
			""")
	int cancelIfStillPending(@Param("id") Long id);
}
