package com.hq.backend.event;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EventActionLogRepository extends JpaRepository<EventActionLog, UUID> {

    boolean existsByClientEventId(UUID clientEventId);

    Optional<EventActionLog> findFirstByNotificationIdOrderByReceivedAtDesc(UUID notificationId);

    @Modifying
    @Query("DELETE FROM EventActionLog a WHERE a.eventId IN :eventIds")
    void deleteAllByEventIdIn(List<UUID> eventIds);
}
