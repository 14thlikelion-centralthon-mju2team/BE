package com.hq.backend.place.dto;

import java.time.Instant;
import java.util.UUID;

public record PlaceVisitResponse(Long id, UUID placeId, Instant enteredAt, Instant exitedAt) {
}
