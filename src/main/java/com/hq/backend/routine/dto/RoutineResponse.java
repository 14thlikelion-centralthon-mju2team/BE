package com.hq.backend.routine.dto;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record RoutineResponse(
        UUID id,
        String title,
        String scheduleType,
        UUID placeId,
        String rrule,
        LocalTime anchorTime,
        List<RoutineTaskResponse> tasks,
        Instant createdAt
) {
}
