package com.hq.backend.wellness.dto;

import java.util.List;

// ai/plan-engine WellnessOutput 계약과 1:1 대응(PR #105). wisScore/wisBand/normalizedLoads는
// 환경 데이터 부족 시 null일 수 있다 — 오류가 아니라 degraded(TRD 절대 원칙 3).
public record WellnessEngineResponse(
        Integer wisScore,
        String wisBand, // low | mid | high, null 가능
        NormalizedLoads normalizedLoads,
        List<WellnessAction> actions,
        boolean eventArmed,
        String weightVersion,
        String contractVersion,
        List<String> degraded
) {

    public record NormalizedLoads(
            double uvLoad, double pmLoad, double thermalLoad, double outdoorLoad, double interestMultiplier) {
    }

    public record WellnessAction(
            String wellnessTopic, String actionCode, String actionLabel, int displayRank, String reason) {
    }
}
