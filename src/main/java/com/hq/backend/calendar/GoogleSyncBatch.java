package com.hq.backend.calendar;

import com.hq.backend.calendar.dto.GoogleCalendarSyncEvent;
import java.util.List;

public record GoogleSyncBatch(List<GoogleCalendarSyncEvent> events, String nextSyncToken) {
}
