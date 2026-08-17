package com.hq.backend.event.dto;

import jakarta.validation.constraints.NotBlank;

public record EventReviewRequest(
        @NotBlank String questionType,
        @NotBlank String userAnswer
) {
}
