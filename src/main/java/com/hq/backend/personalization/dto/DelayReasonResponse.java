package com.hq.backend.personalization.dto;

import com.hq.backend.personalization.EventDelayReason;
import java.math.BigDecimal;

public record DelayReasonResponse(String reasonCode, String reasonSource, BigDecimal confidence) {

    public static DelayReasonResponse from(EventDelayReason reason) {
        return new DelayReasonResponse(reason.getReasonCode(), reason.getReasonSource(), reason.getConfidence());
    }
}
