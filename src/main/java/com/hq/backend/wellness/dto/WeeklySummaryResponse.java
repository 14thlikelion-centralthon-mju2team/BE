package com.hq.backend.wellness.dto;

import java.time.LocalDate;
import java.util.List;

// 주간 리포트(CAL-06). 비율은 분모가 0이면 0이 아니라 null이다 — 표본이 없는 것과
// 0%인 것은 다르고, 클라이언트가 "—"로 구분해 표시할 수 있어야 한다. 그래서 비율마다
// 표본 수를 함께 싣는다.
public record WeeklySummaryResponse(
        LocalDate weekStart,
        LocalDate weekEnd,
        int managedEventCount,
        Double onTimeRate,
        int onTimeSampleCount,
        Integer averageSlackMinutes,
        int averageSlackSampleCount,
        List<PrepAccuracyPoint> prepAccuracy,
        Double wellnessCompletionRate,
        int wellnessProposedCount,
        int wellnessCompletedCount,
        int outdoorMinutes,
        int outdoorSampleCount,
        String outdoorSource
) {

    /** 하루치 준비 시간 예측 대비 실제. 둘 다 기록된 일정만 센다. */
    public record PrepAccuracyPoint(
            LocalDate date, int predictedMinutes, int actualMinutes, int sampleCount) {
    }
}
