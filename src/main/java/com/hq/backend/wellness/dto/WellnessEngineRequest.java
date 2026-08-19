package com.hq.backend.wellness.dto;

import java.time.Instant;
import java.util.List;

// ai/plan-engine WellnessInput 계약과 1:1 대응(camelCase). M3의 eventState는
// 기본값을 보수적으로 두어 scheduler가 명시적으로 상태를 채우기 전에는 push를 arm하지 않는다.
public record WellnessEngineRequest(
        EnvironmentSnapshot environment,
        Integer estimatedOutdoorMinutes,
        List<WellnessPreference> userPreferences,
        List<PrepItemSnapshot> existingPrepItems,
        EngineConfig config,
        WellnessEventState eventState
) {

    public record EnvironmentSnapshot(
            Integer precipitationProbability, Double feelsLikeCelsius, Double uvIndex, Integer pm10,
            String airGrade, Double feelsLikeMinCelsius, Double feelsLikeMaxCelsius, Instant observedAt) {
    }

    public record WellnessPreference(
            String wellnessTopic, boolean isEnabled, Integer remindIntervalMinutes, int dailyEventCap) {
    }

    public record PrepItemSnapshot(
            String itemId, String itemName, String actionType, String sourceType,
            int appliedMinutes, boolean isSensitive) {
    }

    public record WellnessEventState(
            boolean wellnessEventEnabled, boolean eventInProgress, Integer outdoorRemainingMinutes,
            boolean indoorTransitionEstimated, Integer minutesSinceLastEvent,
            List<String> completedActionCodes, List<String> stopTodayActionCodes,
            int dailyEventCount, List<String> raisedThresholdActionCodes) {

        public static WellnessEventState conservative() {
            return new WellnessEventState(false, false, null, false, null, List.of(), List.of(), 0, List.of());
        }
    }

    public record EngineConfig(
            double wisWeightUv, double wisWeightPm, double wisWeightTemp, double wisWeightOutdoor,
            double interestBoostMax, int outdoorCapMinutes, int wisBandCard, int wisBandEvent, String weightVersion) {
    }
}
