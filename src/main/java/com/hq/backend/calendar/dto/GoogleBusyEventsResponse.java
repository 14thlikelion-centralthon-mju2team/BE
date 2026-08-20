package com.hq.backend.calendar.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record GoogleBusyEventsResponse(
        List<GoogleBusyEvent> items,
        @JsonProperty("nextPageToken") String nextPageToken
) {
}
