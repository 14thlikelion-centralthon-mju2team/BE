package com.hq.backend.calendar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarSourceRepository extends JpaRepository<CalendarSource, UUID> {
    List<CalendarSource> findByCalendarConnectionIdAndDeletedAtIsNullOrderByIsDefaultDescDisplayNameAsc(UUID connectionId);
    Optional<CalendarSource> findByCalendarConnectionIdAndIsDefaultTrueAndDeletedAtIsNull(UUID connectionId);
}
