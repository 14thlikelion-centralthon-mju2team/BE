package com.hq.backend.plan.dto;

import com.hq.backend.plan.PlanPrepItem;
import java.time.Instant;
import java.util.UUID;

public record PrepItemResolveResponse(UUID planPrepItemId, String completionStatus, Instant completedAt) {

    public static PrepItemResolveResponse from(PlanPrepItem item) {
        return new PrepItemResolveResponse(item.getPlanPrepItemId(), item.getCompletionStatus(), item.getCompletedAt());
    }
}
