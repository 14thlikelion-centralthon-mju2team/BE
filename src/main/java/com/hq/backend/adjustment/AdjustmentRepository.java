package com.hq.backend.adjustment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdjustmentRepository extends JpaRepository<Adjustment, UUID> {

    List<Adjustment> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
