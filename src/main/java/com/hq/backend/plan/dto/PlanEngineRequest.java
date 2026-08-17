package com.hq.backend.plan.dto;

import java.time.Instant;
import java.util.List;

// ai/plan-engine의 PlanInput 계약과 1:1 대응(camelCase, extra=forbid이므로 필드 추가 금지).
// 전역 Jackson SNAKE_CASE와 별개로 PlanEngineClientConfig의 전용 ObjectMapper로 직렬화된다.
public record PlanEngineRequest(
        Instant now,
        EventSnapshot event,
        PrepEstimateSnapshot prepEstimate,
        int arrivalBufferMinutes,
        int trafficBufferMinutes,
        RouteSnapshot selectedRoute,
        EnvironmentSnapshot environment,
        List<PrepItemSnapshot> prepItems,
        EngineConfig config
) {

    public record EventSnapshot(Instant startsAt, String anchorMode, Instant fixedDepartAt) {
    }

    public record PrepEstimateSnapshot(int estimatedMinutes, String source, int sampleCount) {
    }

    public record RouteSnapshot(String routeId, int totalMinutes, int walkMinutes, String source, boolean isStale) {
    }

    public record EnvironmentSnapshot(Integer precipitationProbability, Double feelsLikeCelsius, Instant observedAt) {
    }

    public record PrepItemSnapshot(
            String itemId, String itemName, String actionType, String sourceType,
            int appliedMinutes, boolean isSensitive) {
    }

    public record EngineConfig(
            int seedFallbackMinutes, int rainThresholdPercent, int rainExtraPrepMinutes,
            int arrivalBufferDefaultMinutes, int trafficBufferDefaultMinutes) {
    }
}
