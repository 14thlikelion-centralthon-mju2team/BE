package com.hq.backend.setting.dto;

import com.hq.backend.setting.UserSetting;

public record SettingsResponse(
        Integer initialPrepMinutes,
        int arrivalBufferMinutes,
        String notificationSensitivity,
        boolean personalizationEnabled,
        boolean autoManageEnabled,
        boolean wellnessEventEnabled,
        boolean lockscreenHideSensitive
) {

    // V6 마이그레이션 컬럼 기본값 — user_setting 행이 아직 없는 사용자(온보딩 전)용.
    public static final SettingsResponse DEFAULT = new SettingsResponse(null, 10, "normal", true, true, false, true);

    public static SettingsResponse from(UserSetting setting) {
        return new SettingsResponse(
                setting.getInitialPrepMinutes(),
                setting.getArrivalBufferMinutes(),
                setting.getNotificationSensitivity(),
                setting.isPersonalizationEnabled(),
                setting.isAutoManageEnabled(),
                setting.isWellnessEventEnabled(),
                setting.isLockscreenHideSensitive());
    }
}
