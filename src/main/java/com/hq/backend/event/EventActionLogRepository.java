package com.hq.backend.event;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventActionLogRepository extends JpaRepository<EventActionLog, UUID> {

    boolean existsByClientEventId(UUID clientEventId);

    Optional<EventActionLog> findFirstByNotificationIdOrderByReceivedAtDesc(UUID notificationId);

    /** @deprecated Use {@link #deleteAllByUserId(UUID)} for bulk deletion — single subquery is more efficient. */
    @Deprecated
    @Modifying
    @Query("DELETE FROM EventActionLog a WHERE a.eventId IN :eventIds")
    void deleteAllByEventIdIn(List<UUID> eventIds);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM EventActionLog a WHERE a.eventId IN (SELECT e.eventId FROM Event e WHERE e.userId = :userId)")
    void deleteAllByUserId(@Param("userId") UUID userId);
}
