package com.hq.backend.personalization;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventDelayReasonRepository extends JpaRepository<EventDelayReason, EventDelayReasonId> {
}
