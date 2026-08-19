package com.hq.backend.wellness.dto;

import java.time.LocalDate;
import java.util.List;

/** Response for M3 POST /internal/v1/wellness/daily-summary. */
public record DailySummaryEngineResponse(
        LocalDate summaryDate, int eventCount, Integer totalOutdoorMinutes,
        Double avgWisWeighted, Double avgRls, Integer dwlScore, String dwlBand,
        String cardScenario, String cardMessage, boolean cardVisible,
        String weightVersion, String contractVersion, List<String> degraded
) {
}
