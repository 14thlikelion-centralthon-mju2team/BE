package com.hq.backend.gapcheck.dto;

import com.hq.backend.gapcheck.GapResponse;
import java.time.Instant;
import java.time.LocalDate;

public record GapCheckResponse(
        LocalDate logDate,
        GapResponse response,
        Instant recordedAt
) {
}
