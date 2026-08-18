package com.hq.backend.plan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRevisionRepository extends JpaRepository<PlanRevision, UUID> {

    Optional<PlanRevision> findByEventIdAndPlanStatus(UUID eventId, String planStatus);

    List<PlanRevision> findByEventIdOrderByRevisionNoDesc(UUID eventId);

    List<PlanRevision> findByEventIdIn(List<UUID> eventIds);
}
