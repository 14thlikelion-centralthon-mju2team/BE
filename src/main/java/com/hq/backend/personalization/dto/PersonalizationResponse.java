package com.hq.backend.personalization.dto;

import java.util.List;

public record PersonalizationResponse(
        List<PrepEstimateResponse> estimates,
        int trafficBufferMinutes,
        int notificationLeadMinutes
) {
}
