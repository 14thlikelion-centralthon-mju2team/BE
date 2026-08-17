package com.hq.backend.bootstrap.dto;

import java.util.UUID;

public record PlaceSummary(
        UUID placeId,
        String placeType,
        String placeName,
        String address,
        double lat,
        double lng,
        boolean isPrimary
) {
}
