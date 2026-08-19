package com.hq.backend.wellness.dto;

/** Request for M3 POST /internal/v1/wellness/rush-load. */
public record RushLoadEngineRequest(
        String eventId,
        double prepDelayMinutes,
        double departDelayMinutes,
        int criticalAlertCount,
        WellnessEngineRequest.EngineConfig config
) {
}
