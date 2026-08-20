package com.hq.backend.event.classification;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventClassificationReviewRepository;
import com.hq.backend.event.EventRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventClassificationReviewWriter {

    private final EventRepository eventRepository;
    private final EventClassificationReviewRepository reviewRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreateReviewOutcome createIfEligible(
            UUID eventId, EventClassificationResult result, Instant askedAt) {
        if (eventId == null || result == null || askedAt == null) {
            return CreateReviewOutcome.STALE;
        }
        Event event = eventRepository.findByIdForUpdate(eventId).orElse(null);
        if (!isEligible(event)) {
            return CreateReviewOutcome.STALE;
        }
        int inserted = reviewRepository.insertPendingIfAbsent(
                UUID.randomUUID(), eventId, result.questionType(), result.suggestedValue(), result.resolvedModel(),
                result.confidence(), askedAt, result.provider(), result.classifierVersion(), result.promptVersion(),
                result.schemaVersion());
        return inserted == 1 ? CreateReviewOutcome.CREATED : CreateReviewOutcome.DUPLICATE;
    }

    private boolean isEligible(Event event) {
        return event != null
                && "undecided".equals(event.getLocationState())
                && "planned".equals(event.getStatus())
                && !event.isAutoManageExcluded()
                && event.getMeetingUrl() == null;
    }
}
