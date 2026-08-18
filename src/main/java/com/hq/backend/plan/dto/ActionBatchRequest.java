package com.hq.backend.plan.dto;

import com.hq.backend.event.ActionSource;
import com.hq.backend.event.ActionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// API 명세 §13 — 오프라인 큐의 최종 도착지. 배치 최대 100건.
public record ActionBatchRequest(@NotEmpty @Size(max = 100) @Valid List<ActionItem> actions) {

    public record ActionItem(
            @NotNull ActionType actionType,
            @NotNull ActionSource actionSource,
            @NotNull Instant deviceTs,
            @NotNull UUID clientEventId,
            @DecimalMin("0") @DecimalMax("1") BigDecimal confidence
    ) {
    }
}
