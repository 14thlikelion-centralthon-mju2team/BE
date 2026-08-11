package com.hq.backend.place.dto;

import java.time.Instant;
import java.util.UUID;

public record PlaceResponse(
        UUID id,
        String label,
        double lat,
        double lng,
        int radiusM,
        String kakaoPlaceId,
        Instant createdAt
) {
}
