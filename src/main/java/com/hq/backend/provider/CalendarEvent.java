package com.hq.backend.provider;

import java.time.Instant;

// TRD 11.3. 캘린더 동기화 계약 — 읽기 전용. attendees·description은 저장 컬럼이 없어(TRD 11.3) 여기 포함하지 않는다.
public record CalendarEvent(
        String externalEventId,
        String etag,
        Instant startsAt,
        Instant endsAt,
        String destinationName,
        Double destinationLat,
        Double destinationLng,
        String status, // confirmed | cancelled
        String provider
) {
}
