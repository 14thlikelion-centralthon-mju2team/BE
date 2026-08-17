package com.hq.backend.plan;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanContextRepository extends JpaRepository<PlanContext, UUID> {
}
