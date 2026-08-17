package com.hq.backend.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, UUID> {

    // rangeStart~rangeEnd와 겹치는 일정 — startsAt < rangeEnd && endsAt > rangeStart.
    // CalendarService의 캘린더 밀도 계산이 사용한다(구 UserEventRepository 대체).
    List<Event> findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(
            UUID userId, Instant rangeEnd, Instant rangeStart);
}
