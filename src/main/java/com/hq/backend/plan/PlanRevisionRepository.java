package com.hq.backend.plan;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRevisionRepository extends JpaRepository<PlanRevision, UUID> {
}
