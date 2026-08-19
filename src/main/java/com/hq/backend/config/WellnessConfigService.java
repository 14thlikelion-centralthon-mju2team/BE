package com.hq.backend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.hq.backend.wellness.dto.WellnessEngineRequest;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class WellnessConfigService {
    private static final Collection<String> KEYS = java.util.List.of(
            "wellness_weight_version", "wis_weights", "wis_interest_boost_max", "outdoor_cap_min",
            "wis_band_card", "wellness_event_min", "wellness_event_min_raised", "daily_event_cap_default",
            "mid_band_action_cap", "uv_high_index", "uv_full_load_index", "pm_loads", "comfort_celsius",
            "heat_extreme_celsius", "cold_extreme_celsius", "rain_thresholds", "temp_swing_flag_celsius",
            "rls_weights", "rls_delay_full_load_min", "rls_critical_alert_full_count", "dwl_weights",
            "dwl_bands", "card_rushed_rls", "card_density_event_count", "card_exposure_outdoor_min");
    private final EngineConfigRepository repository;

    public WellnessConfigService(EngineConfigRepository repository) { this.repository = repository; }

    public WellnessEngineRequest.EngineConfig current() {
        Map<String, JsonNode> values = repository.findByConfigKeyIn(KEYS).stream()
                .collect(Collectors.toMap(EngineConfigEntry::getConfigKey, EngineConfigEntry::getConfigValue));
        Function<String, JsonNode> v = values::get;
        return new WellnessEngineRequest.EngineConfig(
                nestedDouble(v.apply("wis_weights"), "uv", .35), nestedDouble(v.apply("wis_weights"), "pm", .25),
                nestedDouble(v.apply("wis_weights"), "temp", .20), nestedDouble(v.apply("wis_weights"), "outdoor", .20),
                number(v.apply("wis_interest_boost_max"), 1.25), integer(v.apply("outdoor_cap_min"), 120),
                integer(v.apply("wis_band_card"), 40), integer(v.apply("wellness_event_min"), 70),
                text(v.apply("wellness_weight_version"), "m3-wellness-1.0.0"),
                number(v.apply("uv_high_index"), 6), number(v.apply("uv_full_load_index"), 10),
                nestedDouble(v.apply("pm_loads"), "moderate", .25), nestedDouble(v.apply("pm_loads"), "bad", .70), nestedDouble(v.apply("pm_loads"), "very_bad", 1),
                nestedDouble(v.apply("comfort_celsius"), "min", 5), nestedDouble(v.apply("comfort_celsius"), "max", 28),
                number(v.apply("heat_extreme_celsius"), 33), number(v.apply("cold_extreme_celsius"), -12),
                nestedInt(v.apply("rain_thresholds"), "light", 30), nestedInt(v.apply("rain_thresholds"), "heavy", 60), nestedDouble(v.apply("rain_thresholds"), "thermal_bonus", .30), number(v.apply("temp_swing_flag_celsius"), 10),
                integer(v.apply("wellness_event_min_raised"), 85), integer(v.apply("daily_event_cap_default"), 1), integer(v.apply("mid_band_action_cap"), 2),
                nestedDouble(v.apply("rls_weights"), "prep_delay", .45), nestedDouble(v.apply("rls_weights"), "depart_delay", .35), nestedDouble(v.apply("rls_weights"), "critical_alert", .20),
                integer(v.apply("rls_delay_full_load_min"), 30), integer(v.apply("rls_critical_alert_full_count"), 2),
                nestedDouble(v.apply("dwl_weights"), "wis", .60), nestedDouble(v.apply("dwl_weights"), "rls", .40),
                nestedInt(v.apply("dwl_bands"), "mid", 40), nestedInt(v.apply("dwl_bands"), "high", 70),
                integer(v.apply("card_rushed_rls"), 70), integer(v.apply("card_density_event_count"), 4), integer(v.apply("card_exposure_outdoor_min"), 90));
    }
    private double number(JsonNode n, double d) { return n != null && n.isNumber() ? n.asDouble() : d; }
    private int integer(JsonNode n, int d) { return n != null && n.isInt() ? n.asInt() : d; }
    private String text(JsonNode n, String d) { return n != null && n.isTextual() ? n.asText() : d; }
    private double nestedDouble(JsonNode n, String key, double d) { return n != null && n.path(key).isNumber() ? n.path(key).asDouble() : d; }
    private int nestedInt(JsonNode n, String key, int d) { return n != null && n.path(key).isInt() ? n.path(key).asInt() : d; }
}
