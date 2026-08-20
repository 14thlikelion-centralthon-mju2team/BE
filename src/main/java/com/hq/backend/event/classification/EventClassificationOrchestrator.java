package com.hq.backend.event.classification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EventClassificationOrchestrator {

    private final AiClassificationGate gate;
    private final CalendarTitleNormalizer titleNormalizer;
    private final AiClassificationConcurrencyGuard concurrencyGuard;
    private final EventClassifier classifier;
    private final EventClassificationReviewWriter reviewWriter;

    public ClassificationAttemptOutcome classifyCreated(
            UUID userId, UUID eventId, String rawTitle, int remainingProviderCalls) {
        if (remainingProviderCalls <= 0) {
            return ClassificationAttemptOutcome.SKIPPED_BUDGET;
        }
        AiGateOutcome gateOutcome = gate.evaluate(userId);
        if (gateOutcome != AiGateOutcome.ALLOWED) {
            return skippedGateOutcome(gateOutcome);
        }
        Optional<String> normalizedTitle = titleNormalizer.normalize(rawTitle);
        if (normalizedTitle.isEmpty() || eventId == null) {
            return ClassificationAttemptOutcome.SKIPPED_INVALID_INPUT;
        }
        if (!concurrencyGuard.tryAcquire()) {
            return ClassificationAttemptOutcome.SKIPPED_BUSY;
        }
        try {
            Optional<EventClassificationResult> result = classifier.classify(new EventClassificationInput(normalizedTitle.get()));
            if (result.isEmpty()) {
                return ClassificationAttemptOutcome.PROVIDER_EMPTY;
            }
            return switch (reviewWriter.createIfEligible(eventId, result.get(), Instant.now())) {
                case CREATED -> ClassificationAttemptOutcome.REVIEW_CREATED;
                case DUPLICATE -> ClassificationAttemptOutcome.REVIEW_DUPLICATE;
                case STALE -> ClassificationAttemptOutcome.REVIEW_STALE;
            };
        } catch (RuntimeException ignored) {
            // Classification/review failures must not block calendar sync-token advancement.
            return ClassificationAttemptOutcome.PROVIDER_EMPTY;
        } finally {
            concurrencyGuard.release();
        }
    }

    private ClassificationAttemptOutcome skippedGateOutcome(AiGateOutcome outcome) {
        return switch (outcome) {
            case SKIPPED_CONSENT -> ClassificationAttemptOutcome.SKIPPED_CONSENT;
            case SKIPPED_ROLLOUT -> ClassificationAttemptOutcome.SKIPPED_ROLLOUT;
            case DISABLED -> ClassificationAttemptOutcome.SKIPPED_DISABLED;
            case ALLOWED -> throw new IllegalArgumentException("allowed gate outcome cannot be skipped");
        };
    }
}
