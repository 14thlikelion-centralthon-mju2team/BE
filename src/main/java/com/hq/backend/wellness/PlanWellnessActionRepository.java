package com.hq.backend.wellness;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanWellnessActionRepository extends JpaRepository<PlanWellnessAction, UUID> {

    List<PlanWellnessAction> findByPlanId(UUID planId);
}
