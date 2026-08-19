package com.hq.backend.route;

import java.time.Instant;
import java.util.UUID;

public record RouteSearchRequest(
        UUID originPlaceId,
        Double originLat,
        Double originLng,
        Double destLat,
        Double destLng,
        String destName,
        String anchorMode,
        Instant at
) {
}
