package com.hq.backend.metrics;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductEventRepository extends JpaRepository<ProductEvent, UUID> {
}
