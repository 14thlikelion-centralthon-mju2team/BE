package com.hq.backend.event;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventActionLogRepository extends JpaRepository<EventActionLog, UUID> {

    boolean existsByClientEventId(UUID clientEventId);
}
