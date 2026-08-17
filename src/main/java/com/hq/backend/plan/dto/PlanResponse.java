package com.hq.backend.plan.dto;

import com.hq.backend.plan.PlanRevision;
import java.time.Instant;
import java.util.UUID;

// API 명세 §9.2 — planStatus(active|superseded, 리비전 관리)와 eventStatus(생명주기)는
// 다른 축이라 절대 하나로 합치지 않는다. 여기엔 plan_revision의 리비전 상태만 담긴다.
public record PlanResponse(
        UUID planId,
        UUID eventId,
        int revisionNo,
        Instant prepStartAt,
        Instant recommendedDepartAt,
        Instant targetArriveAt,
        Breakdown breakdown,
        boolean feasible,
        String predictionConfidence,
        String planStatus,
        String calcVersion
) {

    public record Breakdown(
            int estimatedPrepMinutes, int extraPrepMinutes, int personalRoutineMinutes,
            int travelMinutes, int trafficBufferMinutes, int arrivalBufferMinutes) {
    }

    public static PlanResponse from(PlanRevision revision) {
        return new PlanResponse(
                revision.getPlanId(),
                revision.getEventId(),
                revision.getRevisionNo(),
                revision.getPrepStartAt(),
                revision.getRecommendedDepartAt(),
                revision.getTargetArriveAt(),
                new Breakdown(
                        revision.getEstimatedPrepMinutes(),
                        revision.getExtraPrepMinutes(),
                        revision.getPersonalRoutineMinutes(),
                        revision.getTravelMinutes(),
                        revision.getTrafficBufferMinutes(),
                        revision.getArrivalBufferMinutes()),
                revision.isFeasible(),
                revision.getPredictionConfidence(),
                revision.getPlanStatus(),
                revision.getCalcVersion());
    }
}
