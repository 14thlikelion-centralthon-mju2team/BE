package com.hq.backend.plan;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanPrepItemRepository extends JpaRepository<PlanPrepItem, UUID> {

    List<PlanPrepItem> findByPlanId(UUID planId);
}
