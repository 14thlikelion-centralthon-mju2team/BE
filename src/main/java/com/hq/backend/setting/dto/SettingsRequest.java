package com.hq.backend.setting.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// API 명세 4.1 — 화면 하나에서 전체 필드를 한 번에 제출하는 계약이라 부분 patch가 아니라
// 전체 필드를 받는다. initialPrepMinutes만 "잘 모르겠어요" 선택지 때문에 null 허용.
public record SettingsRequest(
        @Min(0) Integer initialPrepMinutes,
        @NotNull @Min(0) Integer arrivalBufferMinutes,
        @NotBlank String notificationSensitivity,
        @NotNull Boolean personalizationEnabled,
        @NotNull Boolean autoManageEnabled,
        @NotNull Boolean wellnessEventEnabled,
        @NotNull Boolean lockscreenHideSensitive
) {
}
