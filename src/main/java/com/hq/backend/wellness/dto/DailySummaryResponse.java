package com.hq.backend.wellness.dto;

import com.hq.backend.wellness.DailyWellnessSummary;
import java.time.LocalDate;
import java.util.UUID;

// API 명세 §12.4. DWL은 건강 점수가 아닌 부담 요약 지표다. 원천 데이터가 없으면
// dwlScore는 null, dwlBand는 unknown이며 low로 가장하지 않는다.
public record DailySummaryResponse(
        UUID summaryId,
        LocalDate summaryDate,
        int eventCount,
        int totalOutdoorMinutes,
        String outdoorSource,
        String dwlBand,
        Short dwlScore,
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
