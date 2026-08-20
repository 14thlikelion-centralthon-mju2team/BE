package com.hq.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hq.backend.event.classification.AiClassificationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EventClassificationReviewRetentionMetricsTest {

    @Test
    void records_purge_delete_counts_without_a_lag_timer_when_no_backlog_remains() {
        EventClassificationReviewRetentionBatchWriter writer = Mockito.mock(EventClassificationReviewRetentionBatchWriter.class);
        EventClassificationReviewRepository repository = Mockito.mock(EventClassificationReviewRepository.class);
        when(writer.purgeBatch(Instant.parse("2026-08-20T12:00:00Z"), Instant.parse("2026-08-21T12:00:00Z"), 500))
                .thenReturn(2);
        when(writer.deleteBatch(Instant.parse("2026-05-23T12:00:00Z"), 500)).thenReturn(3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EventClassificationReviewRetentionService service = new EventClassificationReviewRetentionService(
                writer, repository, Clock.fixed(Instant.parse("2026-08-21T12:00:00Z"), ZoneOffset.UTC), new AiClassificationMetrics(registry));

        service.purgeTitles(Instant.parse("2026-08-20T12:00:00Z"), 500);
        service.deleteExpired(Instant.parse("2026-05-23T12:00:00Z"), 500);

        assertThat(registry.get("ai_classification_retention_purge_total").counter().count()).isEqualTo(2);
        assertThat(registry.get("ai_classification_retention_delete_total").counter().count()).isEqualTo(3);
        assertThat(registry.find("ai_classification_retention_lag_seconds").timer()).isNull();
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
    }

    @Test
    void preserves_purge_batch_count_and_records_overdue_backlog_when_a_later_batch_fails() {
        Instant cutoff = Instant.parse("2026-08-20T12:00:00Z");
        EventClassificationReviewRetentionBatchWriter writer = Mockito.mock(EventClassificationReviewRetentionBatchWriter.class);
        EventClassificationReviewRepository repository = Mockito.mock(EventClassificationReviewRepository.class);
        when(writer.purgeBatch(cutoff, Instant.parse("2026-08-21T12:00:00Z"), 2))
                .thenReturn(2).thenThrow(new IllegalStateException("later batch failed"));
        when(repository.findOldestPurgeEligibleAskedAt(cutoff)).thenReturn(Optional.of(cutoff.minus(Duration.ofHours(3))));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EventClassificationReviewRetentionService service = new EventClassificationReviewRetentionService(
                writer, repository, Clock.fixed(Instant.parse("2026-08-21T12:00:00Z"), ZoneOffset.UTC), new AiClassificationMetrics(registry));

        assertThatThrownBy(() -> service.purgeTitles(cutoff, 2)).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("ai_classification_retention_purge_total").counter().count()).isEqualTo(2);
        assertThat(registry.get("ai_classification_retention_lag_seconds").timer().max(java.util.concurrent.TimeUnit.HOURS))
                .isEqualTo(3);
    }

    @Test
    void preserves_delete_batch_count_and_records_overdue_backlog_when_a_later_batch_fails() {
        Instant cutoff = Instant.parse("2026-05-23T12:00:00Z");
        EventClassificationReviewRetentionBatchWriter writer = Mockito.mock(EventClassificationReviewRetentionBatchWriter.class);
        EventClassificationReviewRepository repository = Mockito.mock(EventClassificationReviewRepository.class);
        when(writer.deleteBatch(cutoff, 2)).thenReturn(2).thenThrow(new IllegalStateException("later batch failed"));
        when(repository.findOldestDeleteEligibleAskedAt(cutoff)).thenReturn(Optional.of(cutoff.minus(Duration.ofHours(4))));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EventClassificationReviewRetentionService service = new EventClassificationReviewRetentionService(
                writer, repository, Clock.fixed(Instant.parse("2026-08-21T12:00:00Z"), ZoneOffset.UTC), new AiClassificationMetrics(registry));

        assertThatThrownBy(() -> service.deleteExpired(cutoff, 2)).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("ai_classification_retention_delete_total").counter().count()).isEqualTo(2);
        assertThat(registry.get("ai_classification_retention_lag_seconds").timer().max(java.util.concurrent.TimeUnit.HOURS))
                .isEqualTo(4);
    }
}
