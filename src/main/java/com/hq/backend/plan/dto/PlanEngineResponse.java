package com.hq.backend.plan.dto;

import java.time.Instant;
import java.util.List;

// ai/plan-engine의 PlanOutput 계약과 1:1 대응(camelCase).
public record PlanEngineResponse(
        Instant prepStartAt,
        Instant recommendedDepartAt,
        Instant targetArriveAt,
        Breakdown breakdown,
        List<Reason> reasons,
        List<ChecklistItem> checklist,
        boolean feasible,
        String predictionConfidence,
        List<String> degraded,
        String calcVersion
) {

    public record Breakdown(
            int estimatedPrepMinutes, int extraPrepMinutes, int personalRoutineMinutes,
            int travelMinutes, int trafficBufferMinutes, int arrivalBufferMinutes) {
    }

    public record Reason(String field, String source, boolean adjusted, String text) {
    }

    public record ChecklistItem(
            String itemName, String actionType, String sourceType,
            int appliedMinutes, boolean isSensitive, String reason) {
    }
}
