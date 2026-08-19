package com.hq.backend.plan.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// API 명세 §9.1. wellnessActions/wellness는 웰니스 엔진(M3)이 아직 없어 항상 빈 값 —
// 시간 계획은 저하 없이 반환하고 웰니스만 조용히 생략한다는 원칙(§9.2 degraded)과 같은 결이다.
public record PlanDetailResponse(
        UUID planId,
        UUID eventId,
        int revisionNo,
        String calcVersion,
        String planStatus,
        String eventStatus,
        boolean feasible,
        String predictionConfidence,
        Instant prepStartAt,
        Instant recommendedDepartAt,
        Instant targetArriveAt,
        Breakdown breakdown,
        List<ReasonItem> reasons,
        List<ChecklistItem> checklist,
        List<WellnessActionItem> wellnessActions,
        WellnessScoreItem wellness,
        ContextItem context,
        UUID selectedRouteOptionId,
        List<String> degraded
) {

    public record Breakdown(
            int estimatedPrepMinutes, int extraPrepMinutes, int personalRoutineMinutes,
            int travelMinutes, int trafficBufferMinutes, int arrivalBufferMinutes) {
    }

    public record ReasonItem(String field, String source, boolean adjusted, String text) {
    }

    public record ChecklistItem(
            UUID planPrepItemId, String itemName, String actionType, String sourceType,
            String completionStatus, boolean isSensitive, int appliedMinutes, String reason) {
    }

    public record WellnessActionItem(
            UUID wellnessActionId, String wellnessTopic, String actionCode, String actionLabel,
            short displayRank, String reason, String completionStatus, Instant respondedAt) {
    }

    public record WellnessScoreItem(
            short wisScore, String wisBand, String weightVersion, Instant calculatedAt) {
    }

    public record ContextItem(
            Integer uvIndex, Integer pm10, Integer pm25, BigDecimal feelsLike,
            BigDecimal precipitationProb, Integer estimatedOutdoorMinutes,
            String weatherProvider, String airProvider, Instant observedAt) {
    }
}
