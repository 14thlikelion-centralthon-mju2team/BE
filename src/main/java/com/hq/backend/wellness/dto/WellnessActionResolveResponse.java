package com.hq.backend.wellness.dto;

import com.hq.backend.wellness.PlanWellnessAction;
import java.time.Instant;
import java.util.UUID;

public record WellnessActionResolveResponse(UUID wellnessActionId, String completionStatus, Instant respondedAt) {

    public static WellnessActionResolveResponse from(PlanWellnessAction action) {
        return new WellnessActionResolveResponse(
                action.getWellnessActionId(), action.getCompletionStatus(), action.getRespondedAt());
    }
}
