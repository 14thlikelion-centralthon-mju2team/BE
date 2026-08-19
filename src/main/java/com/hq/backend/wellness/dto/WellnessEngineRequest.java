package com.hq.backend.wellness.dto;

import java.time.Instant;
import java.util.List;

// ai/plan-engine WellnessInput 계약과 1:1 대응(camelCase, PR #105). TRD §7 WIS 계산·
// 웰니스 행동 선택 — 환경 스냅샷과 사용자 선호를 넘기면 점수와 행동 후보(최대 3개)를 받는다.
public record WellnessEngineRequest(
        EnvironmentSnapshot environment, // 환경 제공자 실패 시 null — degraded로 처리(엔진 쪽)
        Integer estimatedOutdoorMinutes,
        List<WellnessPreference> userPreferences,
        List<PrepItemSnapshot> existingPrepItems,
        EngineConfig config
) {

    public record EnvironmentSnapshot(
            Integer precipitationProbability, Double feelsLikeCelsius, Double uvIndex, Integer pm10, Instant observedAt) {
    }

    public record WellnessPreference(
            String wellnessTopic, boolean isEnabled, Integer remindIntervalMinutes, int dailyEventCap) {
    }

    public record PrepItemSnapshot(
            String itemId, String itemName, String actionType, String sourceType,
            int appliedMinutes, boolean isSensitive) {
    }

    // WellnessEngineConfig(PR #105) 필드명 그대로 — DB의 wis_weights 단일 JSON과 키 구조가
    // 다르다는 걸 리뷰에서 지적했으므로(PR #105), 합의 전까지는 Plan과 동일하게 상수로 채운다.
    public record EngineConfig(
            double wisWeightUv, double wisWeightPm, double wisWeightTemp, double wisWeightOutdoor,
            double interestBoostMax, int outdoorCapMinutes, int wisBandCard, int wisBandEvent, String weightVersion) {
    }
}
