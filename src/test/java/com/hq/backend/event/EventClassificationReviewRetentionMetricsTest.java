package com.hq.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.hq.backend.event.classification.AiClassificationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EventClassificationReviewRetentionMetricsTest {

    @Test
    void records_purge_delete_counts_and_nonnegative_lag_without_review_identifiers() {
        EventClassificationReviewRetentionBatchWriter writer = Mockito.mock(EventClassificationReviewRetentionBatchWriter.class);
        when(writer.purgeBatch(Instant.parse("2026-08-20T12:00:00Z"), Instant.parse("2026-08-21T12:00:00Z"), 500))
                .thenReturn(2);
        when(writer.deleteBatch(Instant.parse("2026-05-23T12:00:00Z"), 500)).thenReturn(3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EventClassificationReviewRetentionService service = new EventClassificationReviewRetentionService(
                writer, Clock.fixed(Instant.parse("2026-08-21T12:00:00Z"), ZoneOffset.UTC), new AiClassificationMetrics(registry));

        service.purgeTitles(Instant.parse("2026-08-20T12:00:00Z"), 500);
        service.deleteExpired(Instant.parse("2026-05-23T12:00:00Z"), 500);

        assertThat(registry.get("ai_classification_retention_purge_total").counter().count()).isEqualTo(2);
        assertThat(registry.get("ai_classification_retention_delete_total").counter().count()).isEqualTo(3);
        assertThat(registry.get("ai_classification_retention_lag_seconds").timer().count()).isEqualTo(2);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
    }
}
