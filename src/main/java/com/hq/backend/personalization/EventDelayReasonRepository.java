package com.hq.backend.personalization;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventDelayReasonRepository extends JpaRepository<EventDelayReason, EventDelayReasonId> {

    List<EventDelayReason> findByEventId(UUID eventId);
}
