package com.hq.backend.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record EventReviewRequest(
        @NotNull UUID reviewId,
        @NotBlank String questionType,
        @NotBlank String userAnswer
) {
}
