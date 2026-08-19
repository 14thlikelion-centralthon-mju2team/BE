package com.hq.backend.wellness.dto;

import java.time.LocalDate;
import java.util.List;

/** Request for M3 POST /internal/v1/wellness/daily-summary. */
public record DailySummaryEngineRequest(
        LocalDate summaryDate,
        List<EventSummary> events,
        int proposedActionCount,
        int completedActionCount,
        int criticalAlertCount,
        WellnessEngineRequest.EngineConfig config
) {
    public record EventSummary(
            String eventId, Integer wisScore, Integer rushLoadScore,
            Integer outdoorMinutes, boolean outdoorObserved) {
    }
}
