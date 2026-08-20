package com.hq.backend.calendar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarSourceRepository extends JpaRepository<CalendarSource, UUID> {
    List<CalendarSource> findByCalendarConnectionIdAndDeletedAtIsNullOrderByIsDefaultDescDisplayNameAsc(UUID connectionId);
    Optional<CalendarSource> findByCalendarConnectionIdAndIsDefaultTrueAndDeletedAtIsNull(UUID connectionId);

    @Modifying
    @Query(value = """
            insert into calendar_source (
                calendar_source_id, calendar_connection_id, external_calendar_id, display_name,
                is_writable, is_default, sync_enabled
            ) values (gen_random_uuid(), :connectionId, :externalCalendarId, :displayName, true, true, true)
            on conflict (calendar_connection_id, external_calendar_id) do nothing
            """, nativeQuery = true)
    int insertDefaultSourceIfAbsent(
            @Param("connectionId") UUID connectionId,
            @Param("externalCalendarId") String externalCalendarId,
            @Param("displayName") String displayName);
}
