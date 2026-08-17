package com.hq.backend.plan;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanPrepItemRepository extends JpaRepository<PlanPrepItem, UUID> {
}
