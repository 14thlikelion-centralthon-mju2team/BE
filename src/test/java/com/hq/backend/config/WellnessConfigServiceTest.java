package com.hq.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class WellnessConfigServiceTest {

    @Autowired
    private WellnessConfigService wellnessConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void approved_v1_seed_is_mapped_to_the_full_typed_m3_contract() {
        var config = wellnessConfigService.current();

        assertThat(config.weightVersion()).isEqualTo("m3-wellness-1.0.0");
        assertThat(config.wisWeightUv()).isEqualTo(0.35);
        assertThat(config.wisBandEvent()).isEqualTo(70);
        assertThat(config.wellnessEventMinRaised()).isEqualTo(85);
        assertThat(config.heatExtremeCelsius()).isEqualTo(33.0);
        assertThat(config.coldExtremeCelsius()).isEqualTo(-12.0);
        assertThat(config.rlsDelayFullLoadMinutes()).isEqualTo(30);
        assertThat(config.cardDensityEventCount()).isEqualTo(4);
        assertThat(config.cardExposureOutdoorMinutes()).isEqualTo(90);
    }

    @Test
    void current_database_override_is_used_without_a_redeploy() {
        jdbcTemplate.update("update engine_config set config_value = '75'::jsonb where config_key = 'wellness_event_min'");
        jdbcTemplate.update("update engine_config set config_value = '95'::jsonb where config_key = 'wellness_event_min_raised'");

        var config = wellnessConfigService.current();

        assertThat(config.wisBandEvent()).isEqualTo(75);
        assertThat(config.wellnessEventMinRaised()).isEqualTo(95);
    }
}
