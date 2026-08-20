package com.hq.backend.event.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class EventClassificationOrchestratorTest {

    private static final Long EVENT_REVISION = 0L;

    @Mock private AiClassificationGate gate;
    @Mock private CalendarTitleNormalizer titleNormalizer;
    @Mock private AiClassificationConcurrencyGuard concurrencyGuard;
    @Mock private EventClassifier classifier;
    @Mock private EventClassificationReviewWriter reviewWriter;
    @Mock private AiClassificationMetrics metrics;

    @Test
    void budget_is_checked_before_every_other_step() {
        EventClassificationOrchestrator orchestrator = orchestrator();

        assertThat(orchestrator.classifyCreated(
                UUID.randomUUID(), UUID.randomUUID(), EVENT_REVISION, "private title", 0))
                .isEqualTo(ClassificationAttemptOutcome.SKIPPED_BUDGET);

        verify(gate, never()).evaluate(any());
        verify(classifier, never()).classify(any());
    }

    @Test
    void gate_rejection_and_invalid_normalized_title_never_call_provider() {
        EventClassificationOrchestrator orchestrator = orchestrator();
        when(gate.evaluate(any())).thenReturn(AiGateOutcome.SKIPPED_CONSENT, AiGateOutcome.ALLOWED);
        when(titleNormalizer.normalize("\u0000private")).thenReturn(Optional.empty());

        assertThat(orchestrator.classifyCreated(
                UUID.randomUUID(), UUID.randomUUID(), EVENT_REVISION, "private", 1))
                .isEqualTo(ClassificationAttemptOutcome.SKIPPED_CONSENT);
        assertThat(orchestrator.classifyCreated(
                UUID.randomUUID(), UUID.randomUUID(), EVENT_REVISION, "\u0000private", 1))
                .isEqualTo(ClassificationAttemptOutcome.SKIPPED_INVALID_INPUT);

        verify(classifier, never()).classify(any());
    }

    @Test
    void exhausted_global_permit_skips_without_calling_provider() {
        EventClassificationOrchestrator orchestrator = orchestrator();
        when(gate.evaluate(any())).thenReturn(AiGateOutcome.ALLOWED);
        when(titleNormalizer.normalize("private")).thenReturn(Optional.of("private"));
        when(concurrencyGuard.tryAcquire()).thenReturn(false);

        assertThat(orchestrator.classifyCreated(
                UUID.randomUUID(), UUID.randomUUID(), EVENT_REVISION, "private", 1))
                .isEqualTo(ClassificationAttemptOutcome.SKIPPED_BUSY);

        verify(classifier, never()).classify(any());
        verify(concurrencyGuard, never()).release();
    }

    @Test
    void provider_is_called_once_without_a_transaction_and_empty_result_releases_permit() {
        EventClassificationOrchestrator orchestrator = orchestrator();
        AtomicBoolean transactionWasActive = new AtomicBoolean(true);
        when(gate.evaluate(any())).thenReturn(AiGateOutcome.ALLOWED);
        when(titleNormalizer.normalize("e\u0301 online meeting")).thenReturn(Optional.of("é online meeting"));
        when(concurrencyGuard.tryAcquire()).thenReturn(true);
        when(classifier.classify(any())).thenAnswer(invocation -> {
            transactionWasActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.empty();
        });

        assertThat(orchestrator.classifyCreated(
                UUID.randomUUID(), UUID.randomUUID(), EVENT_REVISION, "e\u0301 online meeting", 1))
                .isEqualTo(ClassificationAttemptOutcome.PROVIDER_EMPTY);

        assertThat(transactionWasActive.get()).isFalse();
        verify(classifier).classify(new EventClassificationInput("é online meeting"));
        verify(concurrencyGuard).release();
    }

    @Test
    void successful_result_is_written_once_and_maps_writer_outcome() {
        EventClassificationOrchestrator orchestrator = orchestrator();
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(gate.evaluate(userId)).thenReturn(AiGateOutcome.ALLOWED);
        when(titleNormalizer.normalize("private")).thenReturn(Optional.of("private"));
        when(concurrencyGuard.tryAcquire()).thenReturn(true);
        when(classifier.classify(any())).thenReturn(Optional.of(result()));
        when(reviewWriter.createIfEligible(any(), any(), any(), any())).thenReturn(CreateReviewOutcome.CREATED);

        assertThat(orchestrator.classifyCreated(userId, eventId, EVENT_REVISION, "private", 1))
                .isEqualTo(ClassificationAttemptOutcome.REVIEW_CREATED);

        InOrder order = org.mockito.Mockito.inOrder(gate, titleNormalizer, concurrencyGuard, classifier, reviewWriter);
        order.verify(gate).evaluate(userId);
        order.verify(titleNormalizer).normalize("private");
        order.verify(concurrencyGuard).tryAcquire();
        order.verify(classifier).classify(new EventClassificationInput("private"));
        order.verify(reviewWriter).createIfEligible(
                org.mockito.ArgumentMatchers.eq(eventId), org.mockito.ArgumentMatchers.eq(EVENT_REVISION), any(), any());
        verify(concurrencyGuard).release();
    }

    private EventClassificationOrchestrator orchestrator() {
        return new EventClassificationOrchestrator(gate, titleNormalizer, concurrencyGuard, classifier, reviewWriter, metrics);
    }

    private EventClassificationResult result() {
        return new EventClassificationResult("is_online", "online", new BigDecimal("0.94"), "openai",
                "gpt-4o-mini-2024-08-06", "classifier-v1", "prompt-v1", "schema-v1");
    }
}
