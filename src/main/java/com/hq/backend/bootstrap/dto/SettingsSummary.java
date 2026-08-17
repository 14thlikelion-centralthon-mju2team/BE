package com.hq.backend.bootstrap.dto;

// UserSetting 엔티티가 아직 없어(M1+) user_setting 컬럼 기본값(V6 마이그레이션)을 그대로 반환한다.
// 실제 저장·수정은 GET/PATCH /me/settings 구현 시점에 이어서 처리.
public record SettingsSummary(
        Integer initialPrepMinutes,
        int arrivalBufferMinutes,
        String notificationSensitivity,
        boolean wellnessEventEnabled,
        boolean lockscreenHideSensitive
) {
}
