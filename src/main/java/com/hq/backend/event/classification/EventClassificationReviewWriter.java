package com.hq.backend.event.classification;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventClassificationReviewRepository;
import com.hq.backend.event.EventRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventClassificationReviewWriter {

    private final EventRepository eventRepository;
    private final EventClassificationReviewRepository reviewRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreateReviewOutcome createIfEligible(
            UUID eventId, Long expectedEventRevision, EventClassificationResult result, Instant askedAt) {
        if (eventId == null || expectedEventRevision == null || result == null || askedAt == null) {
            eventPublisher.publishEvent(new AiReviewMetricEvent(AiReviewOutcome.STALE));
            return CreateReviewOutcome.STALE;
        }
        Event event = eventRepository.findByIdForUpdate(eventId).orElse(null);
        if (!isEligible(event) || !expectedEventRevision.equals(event.getRevision())) {
            eventPublisher.publishEvent(new AiReviewMetricEvent(AiReviewOutcome.STALE));
            return CreateReviewOutcome.STALE;
        }
        int inserted = reviewRepository.insertPendingIfAbsent(
                UUID.randomUUID(), eventId, result.questionType(), result.suggestedValue(), result.resolvedModel(),
                result.confidence(), askedAt, result.provider(), result.classifierVersion(), result.promptVersion(),
                result.schemaVersion());
        CreateReviewOutcome outcome = inserted == 1 ? CreateReviewOutcome.CREATED : CreateReviewOutcome.DUPLICATE;
        eventPublisher.publishEvent(new AiReviewMetricEvent(switch (outcome) {
            case CREATED -> AiReviewOutcome.CREATED;
            case DUPLICATE -> AiReviewOutcome.DUPLICATE;
            case STALE -> AiReviewOutcome.STALE;
        }));
        return outcome;
    }

    private boolean isEligible(Event event) {
        return event != null
                && "undecided".equals(event.getLocationState())
                && "planned".equals(event.getStatus())
                && !event.isAutoManageExcluded()
                && event.getMeetingUrl() == null;
    }
}
