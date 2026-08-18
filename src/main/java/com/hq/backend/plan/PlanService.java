package com.hq.backend.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.dto.PlanDetailResponse;
import com.hq.backend.plan.dto.PlanPatchRequest;
import com.hq.backend.plan.dto.PlanRecalculateResponse;
import com.hq.backend.plan.dto.RouteOptionResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// GET/PATCH /plans/*, /events/{id}/plans/latest, /events/{id}/plan/recalculate, /plans/{id}/routes*
// (API 명세 §9~10). 실제 계산·저장은 PlanCreationService에 맡기고 여기는 조회 조합·소유권
// 검사·리비전 전이 규칙만 담당한다.
@Service
@RequiredArgsConstructor
public class PlanService {

    private static final int MIN_RECALC_CHANGE_MINUTES = 2;

    private final PlanRevisionRepository planRevisionRepository;
    private final RouteOptionRepository routeOptionRepository;
    private final PlanPrepItemRepository planPrepItemRepository;
    private final PlanContextRepository planContextRepository;
    private final EventRepository eventRepository;
    private final PlanCreationService planCreationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(readOnly = true)
    public PlanDetailResponse get(UUID userId, UUID planId) {
        return toDetail(findOwned(userId, planId));
    }

    @Transactional(readOnly = true)
    public PlanDetailResponse getLatestForEvent(UUID userId, UUID eventId) {
        findOwnedEvent(userId, eventId);
        return toDetail(findActive(eventId));
    }

    @Transactional(readOnly = true)
    public List<RouteOptionResponse> listRoutes(UUID userId, UUID planId) {
        PlanRevision revision = findOwned(userId, planId);
        return routeOptionRepository.findByPlanIdOrderByRouteRankAsc(revision.getPlanId()).stream()
                .map(RouteOptionResponse::from)
                .toList();
    }

    // §9.4 — inputHash가 같으면 저장 없이 changed:false. 다르더라도 prepStartAt이 2분 미만
    // 바뀌면 리비전을 만들지 않는다(TRD §8.3) — 계산은 이미 끝났으니 방금 만든 후보를 버린다.
    @Transactional
    public PlanRecalculateResponse recalculate(UUID userId, UUID eventId) {
        Event event = findOwnedEvent(userId, eventId);
        PlanRevision active = findActive(eventId);

        // uq_active_plan_per_event(부분 유니크)가 event_id당 active 리비전 1개만 허용한다.
        // Hibernate는 같은 플러시에서 INSERT를 UPDATE보다 먼저 내보내므로, 새 리비전을 만들기
        // 전에 기존 리비전을 먼저 superseded로 내려서 즉시 flush해야 한다(PlaceService의
        // clearExistingPrimary와 같은 이유). 계산 결과를 버리는 경로에서는 되돌린다.
        active.setPlanStatus("superseded");
        planRevisionRepository.saveAndFlush(active);

        PlanCreationService.RecomputeResult result = planCreationService.recompute(
                userId, event, active.getOriginPlaceId(), active.getRevisionNo() + 1,
                active.getInputHash(), null);

        if (result.revision().isEmpty()) {
            active.setPlanStatus("active");
            return new PlanRecalculateResponse(false, toDetail(active));
        }

        PlanRevision candidate = result.revision().get();
        long deltaMinutes = Math.abs(Duration.between(active.getPrepStartAt(), candidate.getPrepStartAt()).toMinutes());
        if (deltaMinutes < MIN_RECALC_CHANGE_MINUTES) {
            discard(candidate);
            active.setPlanStatus("active");
            return new PlanRecalculateResponse(false, toDetail(active));
        }

        return new PlanRecalculateResponse(true, toDetail(candidate));
    }

    // §9.5 — 사용자 직접 수정은 항상 새 리비전을 만든다(inputHash 비교 없음).
    @Transactional
    public PlanDetailResponse patch(UUID userId, UUID planId, PlanPatchRequest request) {
        PlanRevision active = findOwnedActive(userId, planId);
        Event event = eventRepository.findById(active.getEventId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));

        UUID originPlaceId = request.originPlaceId() != null ? request.originPlaceId() : active.getOriginPlaceId();

        active.setPlanStatus("superseded");
        planRevisionRepository.saveAndFlush(active);

        PlanCreationService.RecomputeResult result = planCreationService.recompute(
                userId, event, originPlaceId, active.getRevisionNo() + 1, null, null);
        PlanRevision newRevision = result.revision()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_PROVIDER_UNAVAILABLE", "계획을 다시 계산할 수 없습니다."));

        if (request.prepStartAt() != null) {
            newRevision.setPrepStartAt(request.prepStartAt());
            newRevision.setReasons(appendUserOverrideReason(newRevision.getReasons()));
        }

        return toDetail(newRevision);
    }

    // §10.2 — 후보를 다시 조회해 같은 routeType을 우선 선택하고 새 리비전을 만든다.
    @Transactional
    public PlanDetailResponse selectRoute(UUID userId, UUID planId, UUID routeOptionId) {
        PlanRevision active = findOwnedActive(userId, planId);
        RouteOption chosen = routeOptionRepository.findById(routeOptionId)
                .filter(r -> r.getPlanId().equals(active.getPlanId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROUTE_OPTION_NOT_FOUND", "경로 후보를 찾을 수 없습니다."));
        Event event = eventRepository.findById(active.getEventId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));

        active.setPlanStatus("superseded");
        planRevisionRepository.saveAndFlush(active);

        PlanCreationService.RecomputeResult result = planCreationService.recompute(
                userId, event, active.getOriginPlaceId(), active.getRevisionNo() + 1, null, chosen.getRouteType());
        PlanRevision newRevision = result.revision()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_PROVIDER_UNAVAILABLE", "계획을 다시 계산할 수 없습니다."));

        return toDetail(newRevision);
    }

    private void discard(PlanRevision revision) {
        planPrepItemRepository.deleteAll(planPrepItemRepository.findByPlanId(revision.getPlanId()));
        routeOptionRepository.deleteAll(routeOptionRepository.findByPlanIdOrderByRouteRankAsc(revision.getPlanId()));
        planContextRepository.findById(revision.getPlanId()).ifPresent(planContextRepository::delete);
        planRevisionRepository.delete(revision);
    }

    private PlanRevision findOwned(UUID userId, UUID planId) {
        PlanRevision revision = planRevisionRepository.findById(planId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));
        eventRepository.findByEventIdAndUserId(revision.getEventId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));
        return revision;
    }

    private PlanRevision findOwnedActive(UUID userId, UUID planId) {
        PlanRevision revision = findOwned(userId, planId);
        if (!"active".equals(revision.getPlanStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_NOT_ACTIVE", "이미 새 리비전으로 대체된 계획입니다.");
        }
        return revision;
    }

    private Event findOwnedEvent(UUID userId, UUID eventId) {
        return eventRepository.findByEventIdAndUserId(eventId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "일정을 찾을 수 없습니다."));
    }

    private PlanRevision findActive(UUID eventId) {
        return planRevisionRepository.findByEventIdAndPlanStatus(eventId, "active")
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "활성 계획이 없습니다."));
    }

    private PlanDetailResponse toDetail(PlanRevision revision) {
        Event event = eventRepository.findById(revision.getEventId()).orElseThrow();
        List<PlanDetailResponse.ChecklistItem> checklist = planPrepItemRepository.findByPlanId(revision.getPlanId())
                .stream()
                .map(this::toChecklistItem)
                .toList();
        PlanContext context = planContextRepository.findById(revision.getPlanId()).orElse(null);

        return new PlanDetailResponse(
                revision.getPlanId(),
                revision.getEventId(),
                revision.getRevisionNo(),
                revision.getCalcVersion(),
                revision.getPlanStatus(),
                event.getStatus(),
                revision.isFeasible(),
                revision.getPredictionConfidence(),
                revision.getPrepStartAt(),
                revision.getRecommendedDepartAt(),
                revision.getTargetArriveAt(),
                new PlanDetailResponse.Breakdown(
                        revision.getEstimatedPrepMinutes(),
                        revision.getExtraPrepMinutes(),
                        revision.getPersonalRoutineMinutes(),
                        revision.getTravelMinutes(),
                        revision.getTrafficBufferMinutes(),
                        revision.getArrivalBufferMinutes()),
                parseReasons(revision.getReasons()),
                checklist,
                List.of(),
                null,
                toContextItem(context),
                revision.getSelectedRouteOptionId(),
                parseDegraded(revision.getDegraded()));
    }

    private PlanDetailResponse.ChecklistItem toChecklistItem(PlanPrepItem item) {
        return new PlanDetailResponse.ChecklistItem(
                item.getPlanPrepItemId(),
                item.getItemNameSnapshot(),
                item.getActionTypeSnapshot(),
                item.getSourceType(),
                item.getCompletionStatus(),
                item.isSensitive(),
                item.getAppliedMinutes(),
                item.getReasonSnapshot());
    }

    private PlanDetailResponse.ContextItem toContextItem(PlanContext context) {
        if (context == null) {
            return null;
        }
        return new PlanDetailResponse.ContextItem(
                context.getUvIndex() != null ? context.getUvIndex().intValue() : null,
                context.getPm10(),
                context.getPm25(),
                context.getFeelsLike(),
                context.getPrecipitationProb(),
                context.getEstimatedOutdoorMinutes(),
                context.getWeatherProvider(),
                context.getAirProvider(),
                context.getObservedAt());
    }

    private List<PlanDetailResponse.ReasonItem> parseReasons(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<PlanDetailResponse.ReasonItem>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private List<String> parseDegraded(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String appendUserOverrideReason(String existingReasonsJson) {
        try {
            List<PlanDetailResponse.ReasonItem> reasons = new java.util.ArrayList<>(
                    objectMapper.readValue(existingReasonsJson, new TypeReference<List<PlanDetailResponse.ReasonItem>>() {
                    }));
            reasons.add(new PlanDetailResponse.ReasonItem("prepStartAt", "user", true, "사용자가 직접 설정"));
            return objectMapper.writeValueAsString(reasons);
        } catch (JsonProcessingException e) {
            return existingReasonsJson;
        }
    }
}
