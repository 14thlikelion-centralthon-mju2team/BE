package com.hq.backend.personalization.dto;

import com.hq.backend.personalization.ArrivalResult;
import com.hq.backend.personalization.EventExecution;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventExecutionResponse(
        UUID eventId,
        UUID finalPlanId,
        Instant actualPrepStartedAt,
        Instant actualDepartedAt,
        Instant actualArrivedAt,
        ArrivalResult arrivalResult,
        String resultSource,
        Integer actualOutdoorMinutes,
        Short rushLoadScore,
        List<DelayReasonResponse> delayReasons
) {

    public static EventExecutionResponse from(EventExecution execution, List<DelayReasonResponse> delayReasons) {
        return new EventExecutionResponse(
                execution.getEventId(),
                execution.getFinalPlanId(),
                execution.getActualPrepStartedAt(),
                execution.getActualDepartedAt(),
                execution.getActualArrivedAt(),
                ArrivalResult.valueOf(execution.getArrivalResult().toUpperCase()),
                execution.getResultSource(),
                execution.getActualOutdoorMinutes(),
                execution.getRushLoadScore(),
                delayReasons);
    }
}
