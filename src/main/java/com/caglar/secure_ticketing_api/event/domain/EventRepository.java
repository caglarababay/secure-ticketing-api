package com.caglar.secure_ticketing_api.event.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update Event e set e.reservedSeats = e.reservedSeats + :seats
			where e.id = :eventId
			  and e.published = true
			  and e.reservedSeats + :seats <= e.capacity
			""")
	int tryReserveSeats(@Param("eventId") Long eventId, @Param("seats") int seats);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update Event e set e.reservedSeats = e.reservedSeats - :seats
			where e.id = :eventId and e.reservedSeats >= :seats
			""")
	int releaseSeats(@Param("eventId") Long eventId, @Param("seats") int seats);
}
