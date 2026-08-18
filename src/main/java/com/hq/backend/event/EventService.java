package com.hq.backend.event;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.dto.EventCreateRequest;
import com.hq.backend.event.dto.EventResponse;
import com.hq.backend.event.dto.EventReviewRequest;
import com.hq.backend.event.dto.EventReviewResponse;
import com.hq.backend.event.dto.EventUpdateRequest;
import com.hq.backend.plan.PlanCreationService;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.dto.PlanResponse;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventService {

    private static final String QUESTION_TYPE_IS_ONLINE = "is_online";
    private static final List<String> EXCLUDED_FROM_NEXT =
            List.of(EventStatus.CANCELLED.name().toLowerCase(), EventStatus.SKIPPED.name().toLowerCase());

    private final EventRepository eventRepository;
    private final EventClassificationReviewRepository classificationReviewRepository;
    private final UserRepository userRepository;
    private final PlanCreationService planCreationService;

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
        Event event = eventRepository
                .findFirstByUserIdAndStartsAtAfterAndStatusNotInOrderByStartsAtAsc(
                        userId, Instant.now(), EXCLUDED_FROM_NEXT)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NEXT_EVENT_NOT_FOUND", "다음 일정이 없습니다."));
        return EventResponse.from(event, timezone);
    }

    @Transactional(readOnly = true)
    public EventResponse get(UUID userId, UUID eventId) {
        return EventResponse.from(findOwned(userId, eventId), timezoneOf(userId));
    }

    @Transactional
    public EventResponse create(UUID userId, EventCreateRequest request) {
        validateDestinationPair(request.destinationLat(), request.destinationLng());
        validateTimeOrder(request.startsAt(), request.endsAt());

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

        // §8.2 — required_resolved면 저장과 동시에 계획을 생성해 응답에 동봉한다. 원점 장소·
        // 경로·엔진 응답 중 하나라도 없으면 PlanCreationService가 조용히 empty를 반환한다 —
        // 계획 생성 실패가 일정 생성 자체를 막지 않는다.
        PlanResponse plan = null;
        if (request.locationState() == LocationState.REQUIRED_RESOLVED) {
            Optional<PlanRevision> revision =
                    planCreationService.createInitialPlan(userId, saved, request.originPlaceId());
            plan = revision.map(PlanResponse::from).orElse(null);
        }

        return EventResponse.from(saved, timezoneOf(userId), plan);
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
        validateDestinationPair(event.getDestinationLat(), event.getDestinationLng());
        validateTimeOrder(event.getStartsAt(), event.getEndsAt());
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
        LocationState resolved;
        if ("offline".equals(request.userAnswer())) {
            resolved = LocationState.REQUIRED_MISSING;
        } else if ("online".equals(request.userAnswer())) {
            resolved = LocationState.NOT_REQUIRED;
        } else {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "questionType이 is_online일 때 userAnswer는 online 또는 offline이어야 합니다.");
        }

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

    // ck_event_destination_pair — 미리 걸러내지 않으면 DB 제약 위반으로 422가 아니라 500이 난다.
    private void validateDestinationPair(Double lat, Double lng) {
        if ((lat == null) != (lng == null)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "destinationLat과 destinationLng는 함께 지정하거나 함께 비워야 합니다.");
        }
    }

    // ck_event_time_order — 위와 같은 이유로 미리 검증한다.
    private void validateTimeOrder(Instant startsAt, Instant endsAt) {
        if (endsAt != null && endsAt.isBefore(startsAt)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "endsAt은 startsAt보다 빠를 수 없습니다.");
        }
    }

    private String timezoneOf(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        return user.getTimezone();
    }
}
