package com.hq.backend.wellness.dto;

import com.hq.backend.wellness.DailyWellnessSummary;
import java.time.LocalDate;
import java.util.UUID;

// API 명세 §12.4. dwlScore는 응답에 담되 클라이언트는 표시하지 않는다(절대 원칙 3) — dwlBand만 노출.
public record DailySummaryResponse(
        UUID summaryId,
        LocalDate summaryDate,
        int eventCount,
        int totalOutdoorMinutes,
        String outdoorSource,
        String dwlBand,
        short dwlScore,
        String cardScenario,
        String message,
        boolean isViewed
) {

    public static DailySummaryResponse from(DailyWellnessSummary summary) {
        return new DailySummaryResponse(
                summary.getSummaryId(),
                summary.getSummaryDate(),
                summary.getEventCount(),
                summary.getTotalOutdoorMinutes(),
                summary.getOutdoorSource(),
                summary.getDwlBand(),
                summary.getDwlScore(),
                summary.getCardScenario(),
                summary.getCardMessageSnapshot(),
                summary.isViewed());
    }
}
