package com.hq.backend.event.dto;

import com.hq.backend.event.LocationState;
import com.hq.backend.event.SourceType;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

// originPlaceId/selectedRouteOptionId/anchorMode/writeToCalendarSourceId는 계획 생성·경로
// 확정·캘린더 기록 시점에만 쓰이는 API 전용 필드다(§8.1). 계획 엔진(이지호)이 아직 없어
// 지금은 계약대로 받기만 하고 실제로 쓰지 않는다 — 엔진 연결 시 EventService에서 소비한다.
public record EventCreateRequest(
        @NotNull Instant startsAt,
        Instant endsAt,
        @NotNull LocationState locationState,
        String destinationName,
        Double destinationLat,
        Double destinationLng,
        String meetingUrl,
        String eventKind,
        @NotNull SourceType sourceType,
        String anchorMode,
        UUID originPlaceId,
        UUID selectedRouteOptionId,
        String displayLabel,
        UUID writeToCalendarSourceId
) {
}
