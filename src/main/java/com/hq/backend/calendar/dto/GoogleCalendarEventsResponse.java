package com.hq.backend.calendar.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

// GET .../calendars/primary/events 응답 중 필요한 필드만 매핑.
public record GoogleCalendarEventsResponse(
        List<GoogleCalendarEvent> items,
        @JsonProperty("nextSyncToken") String nextSyncToken
) {
}
