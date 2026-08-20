package com.hq.backend.event.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PendingEventReviewResponse(
        UUID reviewId,
        UUID eventId,
        Instant startsAt,
        String questionType,
        String suggestedValue,
        BigDecimal classificationConfidence,
        Instant askedAt) {
}
