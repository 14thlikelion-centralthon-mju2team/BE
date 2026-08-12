package com.hq.backend.checkin.dto;

import com.hq.backend.checkin.Condition;
import com.hq.backend.checkin.FocusArea;
import java.time.Instant;
import java.time.LocalDate;

public record CheckinResponse(
        Long id,
        LocalDate logDate,
        Integer availableMinutes,
        Condition conditionInferred,
        Condition conditionFinal,
        Boolean conditionAccepted,
        FocusArea focusArea,
        Instant createdAt
) {
}
