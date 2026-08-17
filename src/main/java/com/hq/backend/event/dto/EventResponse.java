package com.hq.backend.event.dto;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventStatus;
import com.hq.backend.event.LocationState;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

// displayName 해석 순서(§8.3): displayLabel -> destinationName -> "오후 2시 일정".
// 외부 캘린더 제목 원문은 절대 여기 들어가지 않는다 — Event.displayLabel 자체가
// 사용자가 입력·승인한 값만 담는다(절대 원칙 8).
public record EventResponse(
        UUID eventId,
        String displayName,
        Instant startsAt,
        Instant endsAt,
        String timezone,
        LocationState locationState,
        String destinationName,
        Double destinationLat,
        Double destinationLng,
        String meetingUrl,
        String eventKind,
        EventStatus status,
        boolean autoManageExcluded,
        Object plan
) {

    public static EventResponse from(Event event, String timezone) {
        return new EventResponse(
                event.getEventId(),
                resolveDisplayName(event, timezone),
                event.getStartsAt(),
                event.getEndsAt(),
                timezone,
                LocationState.valueOf(event.getLocationState().toUpperCase()),
                event.getDestinationName(),
                event.getDestinationLat(),
                event.getDestinationLng(),
                event.getMeetingUrl(),
                event.getEventKind(),
                EventStatus.valueOf(event.getStatus().toUpperCase()),
                event.isAutoManageExcluded(),
                null);
    }

    private static String resolveDisplayName(Event event, String timezone) {
        if (event.getDisplayLabel() != null && !event.getDisplayLabel().isBlank()) {
            return event.getDisplayLabel();
        }
        if (event.getDestinationName() != null && !event.getDestinationName().isBlank()) {
            return event.getDestinationName();
        }
        ZonedDateTime local = event.getStartsAt().atZone(ZoneId.of(timezone));
        String period = local.getHour() < 12 ? "오전" : "오후";
        int hour12 = local.getHour() % 12 == 0 ? 12 : local.getHour() % 12;
        return period + " " + hour12 + "시 일정";
    }
}
