package com.caglar.secure_ticketing_api.event.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;


public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {
}
