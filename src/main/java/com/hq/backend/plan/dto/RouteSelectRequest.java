package com.hq.backend.plan.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RouteSelectRequest(@NotNull UUID routeOptionId) {
}
