package com.hq.backend.wellness.dto;

/** Response for M3 POST /internal/v1/wellness/rush-load. */
public record RushLoadEngineResponse(
        String eventId,
        int rushLoadScore,
        double prepDelayNorm,
        double departDelayNorm,
        double criticalAlertNorm,
        String weightVersion,
        String contractVersion
) {
}
