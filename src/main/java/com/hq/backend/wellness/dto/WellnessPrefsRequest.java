package com.hq.backend.wellness.dto;

import com.hq.backend.wellness.WellnessTopic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

// API 명세 §4.2 — 준 항목만 갱신한다(부분 patch). 5개 토픽 전부 보낼 필요 없음.
public record WellnessPrefsRequest(@NotEmpty @Valid List<Item> prefs) {

    public record Item(
            @NotNull WellnessTopic wellnessTopic,
            @NotNull Boolean isEnabled,
            @Min(1) Integer remindIntervalMinutes,
            @NotNull @Min(0) Integer dailyEventCap
    ) {
    }
}
