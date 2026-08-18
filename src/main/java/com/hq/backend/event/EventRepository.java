package com.hq.backend.event;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, UUID> {

    List<Event> findByUserId(UUID userId);

    // rangeStart~rangeEnd와 겹치는 일정 — startsAt < rangeEnd && endsAt > rangeStart.
    // CalendarService의 캘린더 밀도 계산이 사용한다(구 UserEventRepository 대체).
    List<Event> findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(
            UUID userId, Instant rangeEnd, Instant rangeStart);

    Optional<Event> findByEventIdAndUserId(UUID eventId, UUID userId);

    List<Event> findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(UUID userId, Instant from, Instant to);

    // /events/next — 취소·건너뛴 일정은 "다음 일정"이 아니다.
    Optional<Event> findFirstByUserIdAndStartsAtAfterAndStatusNotInOrderByStartsAtAsc(
            UUID userId, Instant after, List<String> excludedStatuses);
}
