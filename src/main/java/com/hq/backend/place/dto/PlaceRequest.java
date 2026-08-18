package com.hq.backend.place.dto;

import com.hq.backend.place.PlaceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PlaceRequest(
        @NotNull PlaceType placeType,
        @NotBlank String placeName,
        @NotBlank String address,
        @NotNull Double lat,
        @NotNull Double lng,
        boolean isPrimary
) {
}
