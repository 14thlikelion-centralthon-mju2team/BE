package com.hq.backend.event.classification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CalendarTitleNormalizerTest {

    private final CalendarTitleNormalizer normalizer = new CalendarTitleNormalizer();

    @Test
    void null_blank_and_control_characters_cannot_be_classifier_input() {
        assertThat(normalizer.normalize(null)).isEmpty();
        assertThat(normalizer.normalize(" \t\n")).isEmpty();
        assertThat(normalizer.normalize("meeting\u0000link")).isEmpty();
        assertThat(normalizer.normalize("meeting\u009Flink")).isEmpty();
    }

    @Test
    void unpaired_surrogate_cannot_be_classifier_input() {
        assertThat(normalizer.normalize("meeting\uD800")).isEmpty();
        assertThat(normalizer.normalize("meeting\uDC00")).isEmpty();
    }

    @Test
    void title_over_500_unicode_code_points_cannot_be_classifier_input() {
        assertThat(normalizer.normalize("가".repeat(501))).isEmpty();
    }

    @Test
    void normalizes_to_nfc_without_trimming_and_accepts_500_code_points() {
        String title = "  Cafe\u0301 " + "😀".repeat(493);

        assertThat(normalizer.normalize(title)).contains("  Caf\u00E9 " + "😀".repeat(493));
    }
}
