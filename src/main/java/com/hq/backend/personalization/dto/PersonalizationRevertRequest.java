package com.hq.backend.personalization.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PersonalizationRevertRequest(@NotNull UUID eventId) {
}
