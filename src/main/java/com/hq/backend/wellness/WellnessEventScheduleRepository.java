package com.hq.backend.wellness;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WellnessEventScheduleRepository extends JpaRepository<WellnessEventSchedule, UUID> {
}
