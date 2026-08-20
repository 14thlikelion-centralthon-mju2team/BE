package com.hq.backend.event;

import com.hq.backend.event.classification.AiClassificationMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Drains privacy retention mutations without holding a transaction across batches. */
@Service
public class EventClassificationReviewRetentionService {

    private final EventClassificationReviewRetentionBatchWriter batchWriter;
    private final EventClassificationReviewRepository reviewRepository;
    private final Clock clock;
    private final AiClassificationMetrics metrics;

    public EventClassificationReviewRetentionService(
            EventClassificationReviewRetentionBatchWriter batchWriter,
            EventClassificationReviewRepository reviewRepository,
            Clock clock,
            AiClassificationMetrics metrics) {
        this.batchWriter = batchWriter;
        this.reviewRepository = reviewRepository;
        this.clock = clock;
        this.metrics = metrics;
    }

    public RetentionBatchResult purgeTitles(Instant cutoff, int batchSize) {
        validate(cutoff, batchSize);
        try {
            return drain(batchSize, () -> batchWriter.purgeBatch(cutoff, clock.instant(), batchSize), metrics::addPurged);
        } finally {
            recordBacklogLag(cutoff, () -> reviewRepository.findOldestPurgeEligibleAskedAt(cutoff));
        }
    }

    public RetentionBatchResult deleteExpired(Instant cutoff, int batchSize) {
        validate(cutoff, batchSize);
        try {
            return drain(batchSize, () -> batchWriter.deleteBatch(cutoff, batchSize), metrics::addDeleted);
        } finally {
            recordBacklogLag(cutoff, () -> reviewRepository.findOldestDeleteEligibleAskedAt(cutoff));
        }
    }

    private RetentionBatchResult drain(int batchSize, IntSupplier nextBatch, IntConsumer onSuccessfulBatch) {
        int processed = 0;
        int batch;
        do {
            batch = nextBatch.getAsInt();
            processed += batch;
            onSuccessfulBatch.accept(batch);
        } while (batch == batchSize);
        return new RetentionBatchResult(processed, false);
    }

    private void recordBacklogLag(Instant cutoff, Supplier<Optional<Instant>> oldestEligible) {
        try {
            oldestEligible.get()
                    .filter(askedAt -> askedAt.isBefore(cutoff))
                    .ifPresent(askedAt -> metrics.recordRetentionLag(Duration.between(askedAt, cutoff)));
        } catch (RuntimeException ignored) {
            // Retention mutations must keep their original failure boundary if an optional metric lookup fails.
        }
    }

    private void validate(Instant cutoff, int batchSize) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
    }
}
