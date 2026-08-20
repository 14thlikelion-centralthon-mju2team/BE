package com.hq.backend.event;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EventRepository extends JpaRepository<Event, UUID> {

    List<Event> findByUserId(UUID userId);

    // rangeStart~rangeEnd와 겹치는 일정 — startsAt < rangeEnd && endsAt > rangeStart.
    // CalendarService의 캘린더 밀도 계산이 사용한다(구 UserEventRepository 대체).
    List<Event> findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(
            UUID userId, Instant rangeEnd, Instant rangeStart);

    Optional<Event> findByEventIdAndUserId(UUID eventId, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.eventId = :eventId and e.userId = :userId")
    Optional<Event> findOwnedForUpdate(UUID eventId, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.eventId = :eventId")
    Optional<Event> findByIdForUpdate(UUID eventId);

    Optional<Event> findByExternalEventIdAndUserId(String externalEventId, UUID userId);

    Optional<Event> findByCalendarSourceIdAndExternalEventId(UUID calendarSourceId, String externalEventId);

    List<Event> findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(UUID userId, Instant from, Instant to);

    @org.springframework.data.jpa.repository.Query("""
            SELECT DISTINCT e.userId FROM Event e
            WHERE e.startsAt >= :from AND e.startsAt < :to
            """)
    List<UUID> findDistinctUserIdsByStartsAtBetween(Instant from, Instant to);

    // /events/next — 취소·건너뛴 일정은 "다음 일정"이 아니다.
    Optional<Event> findFirstByUserIdAndStartsAtAfterAndStatusNotInOrderByStartsAtAsc(
            UUID userId, Instant after, List<String> excludedStatuses);

    /** @deprecated Use {@link com.hq.backend.event.EventActionLogRepository#deleteAllByUserId(UUID)} for bulk action log deletion. */
    @Deprecated
    @Query("SELECT e.eventId FROM Event e WHERE e.userId = :userId")
    List<UUID> findAllEventIdsByUserId(UUID userId);
}
