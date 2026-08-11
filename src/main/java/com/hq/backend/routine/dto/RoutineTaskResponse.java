package com.hq.backend.routine.dto;

import java.util.UUID;

public record RoutineTaskResponse(UUID id, UUID actionId, int orderNo) {
}
