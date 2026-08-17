package com.hq.backend.provider;

import java.time.Instant;

// TRD 11.3. 캘린더 동기화 계약 — 읽기 전용. attendees·description은 저장 컬럼이 없어(TRD 11.3) 여기 포함하지 않는다.
public record CalendarEvent(
        String externalEventId,
        String etag,
        // title: CAL-03 분류(PRD 12.2)에만 쓰는 transient 값. attendees·description과 달리 저장 컬럼이
        // 아예 없는 게 아니라(EVENT_CLASSIFICATION_REVIEW.title_snapshot), 동기화 워커가 분류기에 넘기는
        // 용도로만 쓰고 그 외 어디에도 영속화하면 안 된다.
        String title,
        Instant startsAt,
        Instant endsAt,
        String destinationName,
        Double destinationLat,
        Double destinationLng,
        String meetingUrl, // CAL-03 온라인/오프라인 판별 1차 신호(PRD 12.2) — EVENT.meeting_url에 저장
        String status, // confirmed | cancelled
        String provider
) {
}
