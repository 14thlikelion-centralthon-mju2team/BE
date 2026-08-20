package com.hq.backend.event;

import com.hq.backend.event.classification.AiClassificationMetrics;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.IntSupplier;
import org.springframework.stereotype.Service;

/** Drains privacy retention mutations without holding a transaction across batches. */
@Service
public class EventClassificationReviewRetentionService {

    private final EventClassificationReviewRetentionBatchWriter batchWriter;
    private final Clock clock;
    private final AiClassificationMetrics metrics;

    public EventClassificationReviewRetentionService(
            EventClassificationReviewRetentionBatchWriter batchWriter, Clock clock, AiClassificationMetrics metrics) {
        this.batchWriter = batchWriter;
        this.clock = clock;
        this.metrics = metrics;
    }

    public RetentionBatchResult purgeTitles(Instant cutoff, int batchSize) {
        validate(cutoff, batchSize);
        RetentionBatchResult result = drain(batchSize, () -> batchWriter.purgeBatch(cutoff, clock.instant(), batchSize));
        metrics.addPurged(result.processed());
        metrics.recordRetentionLag(java.time.Duration.between(cutoff, clock.instant()));
        return result;
    }

    public RetentionBatchResult deleteExpired(Instant cutoff, int batchSize) {
        validate(cutoff, batchSize);
        RetentionBatchResult result = drain(batchSize, () -> batchWriter.deleteBatch(cutoff, batchSize));
        metrics.addDeleted(result.processed());
        metrics.recordRetentionLag(java.time.Duration.between(cutoff, clock.instant()));
        return result;
    }

    private RetentionBatchResult drain(int batchSize, IntSupplier nextBatch) {
        int processed = 0;
        int batch;
        do {
            batch = nextBatch.getAsInt();
            processed += batch;
        } while (batch == batchSize);
        return new RetentionBatchResult(processed, false);
    }

    private void validate(Instant cutoff, int batchSize) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
    }
}
