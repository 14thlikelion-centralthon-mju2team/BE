package com.hq.backend.calendar.dto;

// 동기화 전용 DTO다. summary는 이 경계에서만 짧게 소비하며 Event에 저장하지 않는다.
public record GoogleCalendarSyncEvent(
        String id,
        String status,
        String summary,
        GoogleEventDateTime start,
        GoogleEventDateTime end
) {
}
