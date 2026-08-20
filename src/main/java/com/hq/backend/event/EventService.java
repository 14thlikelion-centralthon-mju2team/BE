package com.hq.backend.event;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.dto.EventCreateRequest;
import com.hq.backend.event.dto.EventResponse;
import com.hq.backend.event.dto.EventReviewRequest;
import com.hq.backend.event.dto.EventReviewResponse;
import com.hq.backend.event.dto.EventUpdateRequest;
import com.hq.backend.event.dto.PendingEventReviewResponse;
import com.hq.backend.event.classification.AiReviewMetricEvent;
import com.hq.backend.event.classification.AiReviewOutcome;
import com.hq.backend.plan.PlanCreationService;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.dto.PlanResponse;
import com.hq.backend.route.RouteSearchService;
import com.hq.backend.route.SelectedRouteSearch;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.ApplicationEventPublisher;
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
    private final RouteSearchService routeSearchService;
    private final ApplicationEventPublisher eventPublisher;

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

    @Transactional(readOnly = true)
    public List<PendingEventReviewResponse> listPendingReviews(UUID userId, Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to) || to.isAfter(from.plus(31, ChronoUnit.DAYS))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "조회 기간은 최대 31일의 유효한 [from, to) 범위여야 합니다.");
        }
        return classificationReviewRepository.findPendingReviews(userId, from, to);
    }

    @Transactional
    public EventResponse create(UUID userId, EventCreateRequest request) {
        validateDestinationPair(request.destinationLat(), request.destinationLng());
        validateTimeOrder(request.startsAt(), request.endsAt());

        SelectedRouteSearch selectedSearch = request.selectedRouteOptionId() == null
                ? null
                : routeSearchService.consume(userId, request.selectedRouteOptionId());
        validateSelectedSearchRequest(request, selectedSearch);

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
                .updatedAt(Instant.now())
                .build());

        PlanResponse plan = null;
        if (request.locationState() == LocationState.REQUIRED_RESOLVED) {
            Optional<PlanRevision> revision = selectedSearch == null
                    ? planCreationService.createInitialPlan(userId, saved, request.originPlaceId())
                    : planCreationService.createInitialPlan(userId, saved, selectedSearch);
            if (selectedSearch != null && revision.isEmpty()) {
                // selected route를 기본 provider 후보로 조용히 바꾸지 않는다. event 저장도 롤백해
                // 사용자가 재검색/재시도할 수 있는 일관된 상태를 유지한다.
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLAN_CREATION_FAILED",
                        "선택한 경로로 계획을 생성하지 못했습니다. 다시 시도해 주세요.");
            }
            if (revision.isPresent()) {
                if (selectedSearch != null) {
                    routeSearchService.bindToPlan(userId, selectedSearch.routeOptionId(), revision.get().getPlanId());
                }
                plan = PlanResponse.from(revision.get());
            }
        }

        return EventResponse.from(saved, timezoneOf(userId), plan);
    }

    @Transactional
    public EventResponse update(UUID userId, UUID eventId, EventUpdateRequest request) {
        Event event = findOwnedForUpdate(userId, eventId);
        boolean planInputChanged = false;

        if (request.startsAt() != null) {
            event.setStartsAt(request.startsAt());
            planInputChanged = true;
        }
        if (request.endsAt() != null) {
            event.setEndsAt(request.endsAt());
            planInputChanged = true;
        }
        if (request.locationState() != null) {
            // 사용자 지정값은 항상 자동 분류·캘린더 동기화보다 우선한다 (절대 원칙 5)
            event.setLocationState(request.locationState().name().toLowerCase());
            planInputChanged = true;
        }
        if (request.destinationName() != null) {
            event.setDestinationName(request.destinationName());
            planInputChanged = true;
        }
        if (request.destinationLat() != null) {
            event.setDestinationLat(request.destinationLat());
            planInputChanged = true;
        }
        if (request.destinationLng() != null) {
            event.setDestinationLng(request.destinationLng());
            planInputChanged = true;
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
        if (planInputChanged) {
            event.setUpdatedAt(Instant.now());
        }

        if (!isClassificationEligible(event)
                && closePendingReviewForUserChange(event.getEventId(), Instant.now())) {
            eventPublisher.publishEvent(new AiReviewMetricEvent(AiReviewOutcome.CLOSED_BY_USER_PATCH));
        }

        return EventResponse.from(event, timezoneOf(userId));
    }

    @Transactional
    public void delete(UUID userId, UUID eventId) {
        // ERD event에는 deleted_at이 없다 — 삭제는 status='cancelled'로만 표현한다(V6 마이그레이션 참고).
        Event event = findOwnedForUpdate(userId, eventId);
        event.setStatus(EventStatus.CANCELLED.name().toLowerCase());
    }

    @Transactional
    public EventReviewResponse answerReview(UUID userId, UUID eventId, EventReviewRequest request) {
        if (request.questionType() == null || request.questionType().isBlank()
                || request.userAnswer() == null || request.userAnswer().isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "questionType, userAnswer는 필수입니다.");
        }
        Event event = findOwnedForUpdate(userId, eventId);
        EventClassificationReview review = findReviewForUpdate(request.reviewId(), eventId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "분류 확인 질문을 찾을 수 없습니다."));

        if (review.getAnsweredAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_ALREADY_CLOSED", "이미 답변이 완료된 분류 확인 질문입니다.");
        }

        if (!QUESTION_TYPE_IS_ONLINE.equals(request.questionType())
                || !request.questionType().equals(review.getQuestionType())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "지원하지 않는 questionType입니다.");
        }
        if (!isClassificationEligible(event)) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_STALE", "더 이상 답변할 수 없는 분류 확인 질문입니다.");
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
        if (resolved == LocationState.NOT_REQUIRED) {
            event.setMeetingUrl(null);
        }
        eventPublisher.publishEvent(new AiReviewMetricEvent(
                resolved == LocationState.NOT_REQUIRED ? AiReviewOutcome.ANSWERED_ONLINE : AiReviewOutcome.ANSWERED_OFFLINE));

        return new EventReviewResponse(eventId, resolved, true);
    }

    // reviewId가 오면 그 리뷰를, 안 오면 이 event의 미답변 리뷰를 잡는다. 미답변 리뷰는
    // 부분 유니크 인덱스(event_id where answered_at is null)로 event당 최대 1건이라
    // 서버가 고르는 결과가 모호해지지 않는다. 어느 쪽이든 PESSIMISTIC_WRITE로 잠근다.
    private Optional<EventClassificationReview> findReviewForUpdate(UUID reviewId, UUID eventId) {
        if (reviewId != null) {
            return classificationReviewRepository.findByReviewIdAndEventIdForUpdate(reviewId, eventId);
        }
        return classificationReviewRepository
                .findPendingByEventIdForUpdate(eventId, PageRequest.of(0, 1))
                .stream()
                .findFirst();
    }

    private void validateSelectedSearchRequest(EventCreateRequest request, SelectedRouteSearch selectedSearch) {
        if (selectedSearch == null) {
            return;
        }
        if (request.locationState() != LocationState.REQUIRED_RESOLVED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "selectedRouteOptionId는 required_resolved 일정에서만 사용할 수 있습니다.");
        }
        if (request.destinationLat() == null || request.destinationLng() == null
                || Double.compare(request.destinationLat(), selectedSearch.destinationLat()) != 0
                || Double.compare(request.destinationLng(), selectedSearch.destinationLng()) != 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "선택한 경로 후보와 일정 목적지가 일치하지 않습니다.");
        }
        if (request.originPlaceId() != null && !request.originPlaceId().equals(selectedSearch.originPlaceId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "선택한 경로 후보와 일정 출발 장소가 일치하지 않습니다.");
        }
        String requestAnchor = request.anchorMode() == null || request.anchorMode().isBlank()
                ? "arrive_by" : request.anchorMode();
        if (!requestAnchor.equals(selectedSearch.anchorMode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "선택한 경로 후보와 anchorMode가 일치하지 않습니다.");
        }
    }

    private Event findOwned(UUID userId, UUID eventId) {
        return eventRepository.findByEventIdAndUserId(eventId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "일정을 찾을 수 없습니다."));
    }

    private Event findOwnedForUpdate(UUID userId, UUID eventId) {
        return eventRepository.findOwnedForUpdate(eventId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "일정을 찾을 수 없습니다."));
    }

    private boolean closePendingReviewForUserChange(UUID eventId, Instant now) {
        return classificationReviewRepository.findPendingByEventIdForUpdate(eventId, PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(review -> {
                    review.setTitleSnapshot(null);
                    review.setTitlePurgedAt(review.getTitlePurgedAt() != null ? review.getTitlePurgedAt() : now);
                    review.setUserAnswer(null);
                    review.setAnsweredAt(now);
                    return true;
                }).orElse(false);
    }

    private boolean isClassificationEligible(Event event) {
        return "undecided".equals(event.getLocationState())
                && "planned".equals(event.getStatus())
                && !event.isAutoManageExcluded()
                && event.getMeetingUrl() == null;
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
