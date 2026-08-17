package com.hq.backend.event;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventPrepItemRepository extends JpaRepository<EventPrepItem, UUID> {
}
