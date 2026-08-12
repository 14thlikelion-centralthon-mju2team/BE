package com.hq.backend.checkin.dto;

import com.hq.backend.checkin.Condition;
import com.hq.backend.checkin.FocusArea;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CheckinRequest(
        @NotNull LocalDate logDate,
        @NotNull @Min(0) @Max(1440) Integer availableMinutes,
        @NotNull Condition conditionFinal,
        FocusArea focusArea
) {
}
