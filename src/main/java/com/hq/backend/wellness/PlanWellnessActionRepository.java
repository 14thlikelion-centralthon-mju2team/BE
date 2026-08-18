package com.hq.backend.wellness;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanWellnessActionRepository extends JpaRepository<PlanWellnessAction, UUID> {

    Optional<PlanWellnessAction> findByWellnessActionIdAndPlanId(UUID wellnessActionId, UUID planId);
}
