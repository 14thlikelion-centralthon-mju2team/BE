package com.hq.backend.wellness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WellnessEventScheduleRepository extends JpaRepository<WellnessEventSchedule, UUID> {

    List<WellnessEventSchedule> findByPlanIdAndActionCode(UUID planId, String actionCode);

    List<WellnessEventSchedule> findByPlanId(UUID planId);

    Optional<WellnessEventSchedule> findByNotificationId(UUID notificationId);

    List<WellnessEventSchedule> findByPlanIdIn(List<UUID> planIds);
}
