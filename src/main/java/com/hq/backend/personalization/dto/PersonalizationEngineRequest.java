package com.hq.backend.personalization.dto;

import java.time.Instant;

// ai/plan-engine PersonalizationInput 계약과 1:1 대응(camelCase, PR #105). TRD §6 원인 분리
// EMA 보정 — 완료된 일정 하나의 계획 대비 실제 실행 데이터를 넘기면 지연 원인과 보정값을 받는다.
public record PersonalizationEngineRequest(
        String eventId,
        PlannedExecutionSnapshot planned,
        ActualExecutionSnapshot actual,
        EventOutcome outcome,
        CurrentPrepEstimate currentEstimate,
        EngineConfig config
) {

    public record PlannedExecutionSnapshot(
            Instant prepStartAt, Instant recommendedDepartAt, Instant targetArriveAt,
            int estimatedPrepMinutes, int travelMinutes, int trafficBufferMinutes) {
    }

    public record ActualExecutionSnapshot(
            Instant actualPrepStartedAt, Instant actualDepartedAt, Instant actualArrivedAt,
            String resultSource, Integer clockSkewSeconds,
            Instant actualPrepFinishedAt, Double resultConfidence) {
    }

    public record EventOutcome(
            String arrivalResult, String rushAssessment, boolean autoManageExcluded,
            boolean learningReverted, boolean eventModifiedAfterPlan) {
    }

    public record CurrentPrepEstimate(
            double estimatedMinutes, int sampleCount, Double confidence, String modelVersion,
            Double seedMinutes, boolean coldStartAdjusted) {
    }

    // PersonalizationEngineConfig(PR #105) 필드명 그대로 — DB ENGINE_CONFIG 키 매핑은
    // 아직 합의 전이라(PR #105 리뷰 코멘트 참고) Plan과 동일하게 지금은 상수로 채운다.
    public record EngineConfig(
            double prepEmaAlpha, double lateWeight, double earlyWeight, int maxStepMinutes,
            int coldStepMinutes, int prepFloorMinutes, double prepCeilingRatio, String modelVersion) {
    }
}
