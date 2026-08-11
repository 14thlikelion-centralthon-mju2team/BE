package com.hq.backend.adjustment.dto;

import java.time.Instant;
import java.util.UUID;

public record AdjustmentResponse(
        UUID id,
        UUID routineTaskId,
        UUID beforeActionId,
        UUID afterActionId,
        String triggerType,
        String reason,
        Instant createdAt
) {
}
