package com.hq.backend.event.classification;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class AiReviewMetricsListener {

    private final AiClassificationMetrics metrics;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void record(AiReviewMetricEvent event) {
        metrics.recordReview(event.outcome());
    }
}
