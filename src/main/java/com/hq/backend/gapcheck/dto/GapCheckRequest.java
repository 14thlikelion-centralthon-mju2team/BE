package com.hq.backend.gapcheck.dto;

import com.hq.backend.gapcheck.GapResponse;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record GapCheckRequest(
        @NotNull LocalDate logDate,
        @NotNull GapResponse response
) {
}
