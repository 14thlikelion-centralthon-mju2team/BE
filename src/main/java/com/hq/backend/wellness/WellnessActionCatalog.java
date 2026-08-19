package com.hq.backend.wellness;

import java.util.Map;

/**
 * M3 approved wellness action-code catalog shared by the gate and scheduler.
 *
 * <p>Unknown codes deliberately have no topic: treating them as UV would apply
 * another topic's opt-in and daily cap. Legacy M0 values remain readable so
 * previously persisted schedules retain their original behavior.</p>
 */
final class WellnessActionCatalog {

    private static final Map<String, String> TOPICS = Map.ofEntries(
            Map.entry("uv_protect", "uv"),
            Map.entry("uv_reapply", "uv"),
            Map.entry("pm_mask", "pm"),
            Map.entry("pm_recheck", "pm"),
            Map.entry("temp_heat_prep", "temp"),
            Map.entry("temp_cold_prep", "temp"),
            Map.entry("rain_gear", "rain"),
            Map.entry("hydration_intake", "hydration"),
            // M0 persisted values: keep their topic behavior during migration.
            Map.entry("sunscreen", "uv"),
            Map.entry("mask", "pm"),
            Map.entry("hydration", "hydration"),
            Map.entry("outerwear", "temp"),
            Map.entry("umbrella", "rain"));

    private static final Map<String, String> BODIES = Map.ofEntries(
            Map.entry("uv_protect", "자외선 차단 준비를 확인해 주세요."),
            Map.entry("uv_reapply", "설정하신 시간이 지났어요. 자외선 차단제를 다시 바를 타이밍이에요."),
            Map.entry("pm_mask", "미세먼지가 높아요. 마스크를 확인해 주세요."),
            Map.entry("pm_recheck", "미세먼지가 높아요. 마스크 상태를 한 번 확인해 주세요."),
            Map.entry("temp_heat_prep", "더운 날씨예요. 물과 가벼운 복장을 확인해 주세요."),
            Map.entry("temp_cold_prep", "기온이 낮거나 일교차가 커요. 겉옷을 챙기세요."),
            Map.entry("rain_gear", "비 소식이 있어요. 우산이나 방수 준비를 확인해 주세요."),
            Map.entry("hydration_intake", "물 한 잔 마실 시간이에요."),
            // M0 persisted values.
            Map.entry("sunscreen", "설정하신 시간이 지났어요. 자외선 차단제를 다시 바를 타이밍이에요."),
            Map.entry("mask", "미세먼지가 높아요. 마스크를 착용해 주세요."),
            Map.entry("hydration", "물 한 잔 마실 시간이에요."),
            Map.entry("outerwear", "기온이 낮아요. 겉옷을 챙기세요."),
            Map.entry("umbrella", "비 소식이 있어요. 우산을 확인해 주세요."));

    private WellnessActionCatalog() {
    }

    static String topicFor(String actionCode) {
        return TOPICS.get(actionCode);
    }

    static boolean isKnownTopic(String topic) {
        return TOPICS.containsValue(topic);
    }

    static String bodyFor(String actionCode) {
        return BODIES.getOrDefault(actionCode, "웰니스 행동을 확인해 주세요.");
    }
}
