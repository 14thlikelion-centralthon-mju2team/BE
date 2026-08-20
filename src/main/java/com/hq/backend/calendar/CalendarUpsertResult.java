package com.hq.backend.calendar;

import java.util.UUID;

public record CalendarUpsertResult(UUID eventId, CalendarChangeType changeType, boolean requiresPlanRecompute) {
    public boolean isCreated() {
        return changeType == CalendarChangeType.CREATED;
    }
}
