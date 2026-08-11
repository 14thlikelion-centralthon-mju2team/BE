package com.hq.backend.event.dto;

import java.time.Instant;
import java.util.UUID;

public record EventResponse(UUID id, String title, Instant startsAt, Instant endsAt, String placeText, Instant createdAt) {
}
