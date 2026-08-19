package com.hq.backend.metrics;

import java.time.LocalDate;
import java.util.Map;

/** Aggregate-only M3 operational snapshot. It deliberately has no user, location, title, or copy fields. */
public record WellnessOperationalMetrics(
        LocalDate date,
        int wellnessNotificationsScheduled,
        int wellnessNotificationsSent,
        int wellnessNotificationsFailed,
        int wellnessNotificationsCancelled,
        Map<String, Integer> responseActionCounts,
        Map<String, Integer> fallbackReasonCounts,
        int dailySummariesCreated,
        int dailySummariesViewed) {

    public WellnessOperationalMetrics {
        responseActionCounts = Map.copyOf(responseActionCounts);
        fallbackReasonCounts = Map.copyOf(fallbackReasonCounts);
    }
}
