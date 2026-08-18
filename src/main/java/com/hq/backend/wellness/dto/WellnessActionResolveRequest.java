package com.hq.backend.wellness.dto;

import com.hq.backend.wellness.WellnessActionCompletionStatus;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WellnessActionResolveRequest(@NotNull WellnessActionCompletionStatus completionStatus, UUID clientEventId) {
}
