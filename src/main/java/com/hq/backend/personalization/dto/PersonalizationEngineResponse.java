package com.hq.backend.personalization.dto;

// ai/plan-engine PersonalizationOutput 계약과 1:1 대응(PR #105).
public record PersonalizationEngineResponse(
        String cause, // prep_late | prep_overrun | depart_late | traffic | external | unknown
        String adjustedKnob, // prep_estimate | notification_lead | departure_lead | traffic_buffer | none
        Double previousValue,
        Double newValue,
        String adjustmentReason,
        boolean excludedFromLearning,
        String modelVersion,
        String contractVersion
) {
}
