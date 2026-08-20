package com.hq.backend.calendar.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record GoogleCalendarSyncEventsResponse(
        List<GoogleCalendarSyncEvent> items,
        @JsonProperty("nextPageToken") String nextPageToken,
        @JsonProperty("nextSyncToken") String nextSyncToken
) {
}
