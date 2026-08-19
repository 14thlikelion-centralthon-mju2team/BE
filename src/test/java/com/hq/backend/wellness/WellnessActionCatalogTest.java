package com.hq.backend.wellness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WellnessActionCatalogTest {

    @Test
    void m3_action_codes_map_to_their_own_topics_instead_of_defaulting_to_uv() {
        assertThat(WellnessEventGate.actionCodeToTopic("uv_reapply")).isEqualTo("uv");
        assertThat(WellnessEventGate.actionCodeToTopic("pm_mask")).isEqualTo("pm");
        assertThat(WellnessEventGate.actionCodeToTopic("pm_recheck")).isEqualTo("pm");
        assertThat(WellnessEventGate.actionCodeToTopic("temp_heat_prep")).isEqualTo("temp");
        assertThat(WellnessEventGate.actionCodeToTopic("temp_cold_prep")).isEqualTo("temp");
        assertThat(WellnessEventGate.actionCodeToTopic("rain_gear")).isEqualTo("rain");
        assertThat(WellnessEventGate.actionCodeToTopic("hydration_intake")).isEqualTo("hydration");
        assertThat(WellnessEventGate.actionCodeToTopic("unknown")).isNull();
    }

    @Test
    void m3_action_codes_use_approved_topic_specific_copy() {
        assertThat(WellnessActionCatalog.bodyFor("pm_recheck")).contains("미세먼지", "마스크");
        assertThat(WellnessActionCatalog.bodyFor("temp_heat_prep")).contains("물", "복장");
        assertThat(WellnessActionCatalog.bodyFor("rain_gear")).contains("우산");
        assertThat(WellnessActionCatalog.bodyFor("hydration_intake")).contains("물 한 잔");
    }
}
