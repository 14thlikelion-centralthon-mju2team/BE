package com.hq.backend.place.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// radiusM은 null이면 서비스에서 DB 기본값(300)을 그대로 채운다 (V1__init.sql 기본값과 동일).
public record CreatePlaceRequest(
        @NotBlank String label,
        @NotNull Double lat,
        @NotNull Double lng,
        Integer radiusM,
        String kakaoPlaceId
) {
}
