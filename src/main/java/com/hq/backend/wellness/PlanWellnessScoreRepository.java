package com.hq.backend.wellness;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanWellnessScoreRepository extends JpaRepository<PlanWellnessScore, UUID> {
}
