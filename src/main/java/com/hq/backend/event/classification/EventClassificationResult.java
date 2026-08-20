package com.hq.backend.event.classification;

import java.math.BigDecimal;

public record EventClassificationResult(
        String questionType,
        String suggestedValue,
        BigDecimal confidence,
        String provider,
        String resolvedModel,
        String classifierVersion,
        String promptVersion,
        String schemaVersion) {
}
