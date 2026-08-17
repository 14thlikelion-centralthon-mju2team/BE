package com.hq.backend.event;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.dto.EventCreateRequest;
import com.hq.backend.event.dto.EventResponse;
import com.hq.backend.event.dto.EventReviewRequest;
import com.hq.backend.event.dto.EventReviewResponse;
import com.hq.backend.event.dto.EventUpdateRequest;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventService {

    private static final String QUESTION_TYPE_IS_ONLINE = "is_online";

    private final EventRepository eventRepository;
    private final EventClassificationReviewRepository classificationReviewRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<EventResponse> list(UUID userId, Instant from, Instant to) {
        String timezone = timezoneOf(userId);
        return eventRepository.findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(userId, from, to).stream()
                .map(event -> EventResponse.from(event, timezone))
                .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse next(UUID userId) {
        String timezone = timezoneOf(userId);
        Event event = eventRepository.findFirstByUserIdAndStartsAtAfterOrderByStartsAtAsc(userId, Instant.now())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NEXT_EVENT_NOT_FOUND", "다음 일정이 없습니다."));
        return EventResponse.from(event, timezone);
    }

    @Transactional(readOnly = true)
    public EventResponse get(UUID userId, UUID eventId) {
        return EventResponse.from(findOwned(userId, eventId), timezoneOf(userId));
    }

    @Transactional
    public EventResponse create(UUID userId, EventCreateRequest request) {
        Event saved = eventRepository.save(Event.builder()
                .userId(userId)
                .sourceType(request.sourceType().name().toLowerCase())
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .isAllDay(false)
                .locationState(request.locationState().name().toLowerCase())
                .destinationName(request.destinationName())
                .destinationLat(request.destinationLat())
                .destinationLng(request.destinationLng())
                .meetingUrl(request.meetingUrl())
                .eventKind(request.eventKind())
                .displayLabel(request.displayLabel())
                .autoManageExcluded(false)
                .status(EventStatus.PLANNED.name().toLowerCase())
                .createdAt(Instant.now())
                .build());

        // TODO(계획 엔진 연결 전): locationState=required_resolved면 저장과 동시에 계획을
        // 생성해 응답에 동봉해야 한다(§8.2). PlanEngine.compute()가 아직 병합되지 않아 지금은
        // 항상 plan:null을 반환한다 — 이지호의 계산 모듈이 들어오면 여기서 호출한다.
        return EventResponse.from(saved, timezoneOf(userId));
    }

    @Transactional
    public EventResponse update(UUID userId, UUID eventId, EventUpdateRequest request) {
        Event event = findOwned(userId, eventId);

        if (request.startsAt() != null) {
            event.setStartsAt(request.startsAt());
        }
        if (request.endsAt() != null) {
            event.setEndsAt(request.endsAt());
        }
        if (request.locationState() != null) {
            // 사용자 지정값은 항상 자동 분류·캘린더 동기화보다 우선한다 (절대 원칙 5)
            event.setLocationState(request.locationState().name().toLowerCase());
        }
        if (request.destinationName() != null) {
            event.setDestinationName(request.destinationName());
        }
        if (request.destinationLat() != null) {
            event.setDestinationLat(request.destinationLat());
        }
        if (request.destinationLng() != null) {
            event.setDestinationLng(request.destinationLng());
        }
        if ((event.getDestinationLat() == null) != (event.getDestinationLng() == null)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "destinationLat과 destinationLng는 함께 지정하거나 함께 비워야 합니다.");
        }
        if (request.meetingUrl() != null) {
            event.setMeetingUrl(request.meetingUrl());
        }
        if (request.eventKind() != null) {
            event.setEventKind(request.eventKind());
        }
        if (request.displayLabel() != null) {
            event.setDisplayLabel(request.displayLabel());
        }
        if (request.autoManageExcluded() != null) {
            event.setAutoManageExcluded(request.autoManageExcluded());
        }

        return EventResponse.from(event, timezoneOf(userId));
    }

    @Transactional
    public void delete(UUID userId, UUID eventId) {
        // ERD event에는 deleted_at이 없다 — 삭제는 status='cancelled'로만 표현한다(V6 마이그레이션 참고).
        Event event = findOwned(userId, eventId);
        event.setStatus(EventStatus.CANCELLED.name().toLowerCase());
    }

    @Transactional
    public EventReviewResponse answerReview(UUID userId, UUID eventId, EventReviewRequest request) {
        Event event = findOwned(userId, eventId);
        EventClassificationReview review = classificationReviewRepository
                .findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(eventId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "답변할 분류 확인 질문이 없습니다."));

        if (!QUESTION_TYPE_IS_ONLINE.equals(request.questionType())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "지원하지 않는 questionType입니다.");
        }
        LocationState resolved = "offline".equals(request.userAnswer())
                ? LocationState.REQUIRED_MISSING
                : LocationState.NOT_REQUIRED;

        Instant now = Instant.now();
        // ck_title_purged: answered_at을 채우려면 title_snapshot이 이미 NULL이어야 한다 —
        // 같은 트랜잭션에서 폐기와 답변 기록을 함께 처리한다.
        review.setTitleSnapshot(null);
        review.setTitlePurgedAt(review.getTitlePurgedAt() != null ? review.getTitlePurgedAt() : now);
        review.setUserAnswer(request.userAnswer());
        review.setAnsweredAt(now);

        event.setLocationState(resolved.name().toLowerCase());

        return new EventReviewResponse(eventId, resolved, true);
    }

    private Event findOwned(UUID userId, UUID eventId) {
        return eventRepository.findByEventIdAndUserId(eventId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "일정을 찾을 수 없습니다."));
    }

    private String timezoneOf(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        return user.getTimezone();
    }
}
