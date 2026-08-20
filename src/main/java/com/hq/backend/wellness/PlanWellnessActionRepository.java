package com.hq.backend.wellness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanWellnessActionRepository extends JpaRepository<PlanWellnessAction, UUID> {

    List<PlanWellnessAction> findByPlanId(UUID planId);

    // 주간 요약이 한 주치 계획의 웰니스 제안을 한 번에 읽는다.
    List<PlanWellnessAction> findByPlanIdIn(List<UUID> planIds);

    Optional<PlanWellnessAction> findByWellnessActionIdAndPlanId(UUID wellnessActionId, UUID planId);
}
