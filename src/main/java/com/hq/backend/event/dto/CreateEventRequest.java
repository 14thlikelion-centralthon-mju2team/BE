package com.hq.backend.event.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CreateEventRequest(String title, @NotNull Instant startsAt, @NotNull Instant endsAt, String placeText) {
}
