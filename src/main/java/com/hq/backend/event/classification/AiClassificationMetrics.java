package com.hq.backend.event.classification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Records only aggregate AI-classification operational signals. */
@Component
public final class AiClassificationMetrics {

    private static final String CALLS = "ai_classification_calls_total";
    private static final String LATENCY = "ai_classification_latency_seconds";
    private static final String TOKENS = "ai_classification_tokens_total";
    private static final String REVIEWS = "ai_classification_reviews_total";
    private static final String RETENTION_PURGE = "ai_classification_retention_purge_total";
    private static final String RETENTION_DELETE = "ai_classification_retention_delete_total";
    private static final String RETENTION_LAG = "ai_classification_retention_lag_seconds";

    private final MeterRegistry registry;

    public AiClassificationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    public void recordCall(AiCallOutcome outcome) {
        Counter.builder(CALLS).tag("outcome", wireValue(outcome)).register(registry).increment();
    }

    public void recordLatency(Duration duration) {
        recordDuration(LATENCY, duration);
    }

    public void addTokens(TokenDirection direction, long count) {
        if (count <= 0) return;
        Counter.builder(TOKENS).tag("direction", wireValue(direction)).register(registry).increment(count);
    }

    public void recordReview(AiReviewOutcome outcome) {
        Counter.builder(REVIEWS).tag("outcome", wireValue(outcome)).register(registry).increment();
    }

    public void addPurged(int count) {
        add(RetentionMetric.PURGE, count);
    }

    public void addDeleted(int count) {
        add(RetentionMetric.DELETE, count);
    }

    public void recordRetentionLag(Duration lag) {
        recordDuration(RETENTION_LAG, lag);
    }

    private void add(RetentionMetric metric, int count) {
        if (count > 0) Counter.builder(metric.name).register(registry).increment(count);
    }

    private void recordDuration(String name, Duration duration) {
        if (duration != null && !duration.isNegative()) Timer.builder(name).register(registry).record(duration);
    }

    private String wireValue(Enum<?> value) {
        return Objects.requireNonNull(value, "metric outcome must not be null").name().toLowerCase(Locale.ROOT);
    }

    private enum RetentionMetric {
        PURGE(RETENTION_PURGE),
        DELETE(RETENTION_DELETE);

        private final String name;

        RetentionMetric(String name) {
            this.name = name;
        }
    }
}
