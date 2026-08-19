package com.hq.backend.bookmark.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record BookmarkCreateRequest(
        @NotBlank String placeName,
        @NotNull BigDecimal lat,
        @NotNull BigDecimal lng,
        String folder
) {
}
