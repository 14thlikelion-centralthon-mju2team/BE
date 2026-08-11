package com.hq.backend.event;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserEventRepository extends JpaRepository<UserEvent, UUID> {

    List<UserEvent> findByUserIdOrderByStartsAtAsc(UUID userId);

    Optional<UserEvent> findByIdAndUserId(UUID id, UUID userId);

    // rangeStart~rangeEnd와 겹치는 일정 — startsAt < rangeEnd && endsAt > rangeStart.
    List<UserEvent> findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(
            UUID userId, Instant rangeEnd, Instant rangeStart);
}
