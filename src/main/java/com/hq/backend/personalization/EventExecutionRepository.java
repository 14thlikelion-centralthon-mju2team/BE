package com.hq.backend.personalization;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventExecutionRepository extends JpaRepository<EventExecution, UUID> {
}
