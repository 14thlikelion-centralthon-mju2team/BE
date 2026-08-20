package com.hq.backend.event;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Runs each locking native mutation in an isolated, short-lived transaction. */
@Service
public class EventClassificationReviewRetentionBatchWriter {

    private final EventClassificationReviewRepository reviewRepository;

    public EventClassificationReviewRetentionBatchWriter(EventClassificationReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeBatch(Instant cutoff, Instant purgedAt, int batchSize) {
        return reviewRepository.purgeTitleSnapshots(cutoff, purgedAt, batchSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteBatch(Instant cutoff, int batchSize) {
        return reviewRepository.deleteExpiredReviews(cutoff, batchSize);
    }
}
