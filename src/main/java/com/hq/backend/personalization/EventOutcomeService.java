package com.hq.backend.personalization;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.personalization.dto.DelayReasonResponse;
import com.hq.backend.personalization.dto.EventExecutionResponse;
import com.hq.backend.personalization.dto.EventFeedbackRequest;
import com.hq.backend.personalization.dto.EventFeedbackResponse;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventOutcomeService {

    private final EventRepository eventRepository;
    private final EventExecutionRepository eventExecutionRepository;
    private final EventDelayReasonRepository eventDelayReasonRepository;
    private final EventFeedbackRepository eventFeedbackRepository;

    @Transactional(readOnly = true)
    public EventExecutionResponse getExecution(UUID userId, UUID eventId) {
        findOwnedEvent(userId, eventId);
        EventExecution execution = eventExecutionRepository.findById(eventId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "실행 결과가 아직 없습니다."));
        var delayReasons = eventDelayReasonRepository.findByEventId(eventId).stream()
                .map(DelayReasonResponse::from)
                .toList();
        return EventExecutionResponse.from(execution, delayReasons);
    }

    @Transactional
    public EventFeedbackResponse submitFeedback(UUID userId, UUID eventId, EventFeedbackRequest request) {
        findOwnedEvent(userId, eventId);
        EventFeedback feedback = eventFeedbackRepository.findById(eventId)
                .map(existing -> {
                    existing.setPrepTimingAssessment(request.prepTimingAssessment().name().toLowerCase());
                    existing.setArrivalResult(request.arrivalResult() != null
                            ? request.arrivalResult().name().toLowerCase() : null);
                    existing.setRushAssessment(request.rushAssessment() != null
                            ? request.rushAssessment().name().toLowerCase() : null);
                    return existing;
                })
                .orElseGet(() -> eventFeedbackRepository.save(EventFeedback.builder()
                        .eventId(eventId)
                        .prepTimingAssessment(request.prepTimingAssessment().name().toLowerCase())
                        .arrivalResult(request.arrivalResult() != null
                                ? request.arrivalResult().name().toLowerCase() : null)
                        .rushAssessment(request.rushAssessment() != null
                                ? request.rushAssessment().name().toLowerCase() : null)
                        .createdAt(Instant.now())
                        .build()));

        return EventFeedbackResponse.from(feedback);
    }

    private Event findOwnedEvent(UUID userId, UUID eventId) {
        return eventRepository.findByEventIdAndUserId(eventId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "일정을 찾을 수 없습니다."));
    }
}
