package com.hq.backend.wellness.dto;

import com.hq.backend.wellness.UserWellnessPref;
import com.hq.backend.wellness.WellnessTopic;

public record WellnessPrefResponse(
        WellnessTopic wellnessTopic, boolean isEnabled, Integer remindIntervalMinutes, int dailyEventCap) {

    // user_wellness_pref 컬럼 기본값(V6) — 사용자가 아직 건드리지 않은 항목용.
    public static WellnessPrefResponse defaultFor(WellnessTopic topic) {
        return new WellnessPrefResponse(topic, false, null, 1);
    }

    public static WellnessPrefResponse from(UserWellnessPref pref) {
        return new WellnessPrefResponse(
                WellnessTopic.valueOf(pref.getWellnessTopic().toUpperCase()),
                pref.isEnabled(),
                pref.getRemindIntervalMinutes(),
                pref.getDailyEventCap());
    }
}
