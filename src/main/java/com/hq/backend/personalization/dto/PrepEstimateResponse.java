package com.hq.backend.personalization.dto;

import com.hq.backend.personalization.UserPrepEstimate;
import java.math.BigDecimal;
import java.time.Instant;

public record PrepEstimateResponse(
        String scopeType,
        String scopeValue,
        int estimatedMinutes,
        int sampleCount,
        BigDecimal confidence,
        String modelVersion,
        String adjustmentReason,
        Instant validFrom
) {

    public static PrepEstimateResponse from(UserPrepEstimate estimate) {
        return new PrepEstimateResponse(
                estimate.getScopeType(),
                estimate.getScopeValue(),
                estimate.getEstimatedMinutes(),
                estimate.getSampleCount(),
                estimate.getConfidence(),
                estimate.getModelVersion(),
                estimate.getAdjustmentReason(),
                estimate.getValidFrom());
    }
}
