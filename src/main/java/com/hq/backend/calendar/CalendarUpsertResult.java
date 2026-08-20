package com.hq.backend.calendar;

import java.util.UUID;

public record CalendarUpsertResult(
        UUID eventId, CalendarChangeType changeType, boolean requiresPlanRecompute, Long eventRevision) {
    public CalendarUpsertResult(UUID eventId, CalendarChangeType changeType, boolean requiresPlanRecompute) {
        this(eventId, changeType, requiresPlanRecompute, null);
    }

    public boolean isCreated() {
        return changeType == CalendarChangeType.CREATED;
    }
}
