package com.hq.backend.plan;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.ActionSource;
import com.hq.backend.event.ActionType;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventActionLog;
import com.hq.backend.event.EventActionLogRepository;
import com.hq.backend.event.EventRepository;
import com.hq.backend.event.EventStatus;
import com.hq.backend.metrics.ProductEventService;
import com.hq.backend.personalization.ArrivalResult;
import com.hq.backend.personalization.EventDelayReason;
import com.hq.backend.personalization.EventDelayReasonId;
import com.hq.backend.personalization.EventDelayReasonRepository;
import com.hq.backend.personalization.EventExecution;
import com.hq.backend.personalization.EventExecutionRepository;
import com.hq.backend.personalization.PersonalizationEngineClient;
import com.hq.backend.personalization.UserPrepEstimate;
import com.hq.backend.personalization.UserPrepEstimateRepository;
import com.hq.backend.personalization.dto.PersonalizationEngineRequest;
import com.hq.backend.personalization.dto.PersonalizationEngineResponse;
import com.hq.backend.plan.dto.ActionBatchRequest;
import com.hq.backend.plan.dto.ActionBatchResponse;
import com.hq.backend.setting.UserSetting;
import com.hq.backend.setting.UserSettingRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// API 명세 §13 — POST /plans/{planId}/actions. clientEventId UNIQUE(event_action_log)가
// 오프라인 재전송을 흡수하므로 중복은 오류가 아니라 duplicated 카운트로만 반영한다(TR-03).
@Service
@RequiredArgsConstructor
public class PlanActionService {

    private static final long CLOCK_SKEW_TOLERANCE_SECONDS = 120;

    // ponytail: arrivalResult 경계값(10분)은 정확한 기준(AI·기획 확정) 전까지 쓰는 임시값.
    // ARRIVAL_BUFFER_MINUTES 시드값과 같은 스케일이라 선택했다 — 실측 데이터가 쌓이면 조정.
    private static final long EARLY_THRESHOLD_MINUTES = 10;
    private static final long LATE_THRESHOLD_MINUTES = 10;

    // PersonalizationEngineConfig 시드값(V6 prep_ema_alpha=0.30, 나머지는 TRD §6/§15.2
    // 가드레일 상수 — Python 쪽 기본값과 동일). DB 실연결은 PR #105 리뷰의 키 합의 이후.
    private static final double PREP_EMA_ALPHA = 0.30;
    private static final double LATE_WEIGHT = 1.50;
    private static final double EARLY_WEIGHT = 0.70;
    private static final int MAX_STEP_MINUTES = 15;
    private static final int COLD_STEP_MINUTES = 20;
    private static final int PREP_FLOOR_MINUTES = 10;
    private static final double PREP_CEILING_RATIO = 2.0;
    private static final String MODEL_VERSION = "ema-v1";

    private static final Set<ActionType> EXECUTION_RELEVANT_ACTIONS =
            EnumSet.of(ActionType.PREP_STARTED, ActionType.DEPARTED, ActionType.ARRIVED);

    private final PlanRevisionRepository planRevisionRepository;
    private final EventRepository eventRepository;
    private final EventActionLogRepository eventActionLogRepository;
    private final EventExecutionRepository eventExecutionRepository;
    private final EventDelayReasonRepository eventDelayReasonRepository;
    private final UserPrepEstimateRepository userPrepEstimateRepository;
    private final UserSettingRepository userSettingRepository;
    private final PersonalizationEngineClient personalizationEngineClient;
    private final PlanContextRepository planContextRepository;
    private final PlanService planService;
    private final ProductEventService productEventService;

    @Transactional
    public ActionBatchResponse submit(UUID userId, UUID planId, ActionBatchRequest request) {
        PlanRevision revision = findOwned(userId, planId);
        Event event = eventRepository.findById(revision.getEventId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));

        int accepted = 0;
        int duplicated = 0;
        String status = event.getStatus();

        for (ActionBatchRequest.ActionItem item : request.actions()) {
            if (eventActionLogRepository.existsByClientEventId(item.clientEventId())) {
                duplicated++;
                continue;
            }

            Instant receivedAt = Instant.now();
            boolean clockSkew = Duration.between(item.deviceTs(), receivedAt).abs().getSeconds()
                    > CLOCK_SKEW_TOLERANCE_SECONDS;

            eventActionLogRepository.save(EventActionLog.builder()
                    .eventId(event.getEventId())
                    .planId(planId)
                    .actionType(item.actionType().name().toLowerCase())
                    .actionSource(item.actionSource().name().toLowerCase())
                    .actionAt(item.deviceTs())
                    .receivedAt(receivedAt)
                    .confidence(item.confidence())
                    .clockSkew(clockSkew)
                    .clientEventId(item.clientEventId())
                    .build());
            accepted++;

            if (EXECUTION_RELEVANT_ACTIONS.contains(item.actionType())) {
                applyExecutionSideEffect(revision, event, item);
            }
            status = advance(status, item.actionType());
        }

        if (!status.equals(event.getStatus())) {
            event.setStatus(status);
        }

        return new ActionBatchResponse(accepted, duplicated, status, planService.getLatestForEvent(userId, event.getEventId()));
    }

    // event_execution은 일정당 1건(약한 엔티티) — prep_started/departed/arrived가 들어올 때마다
    // 없으면 만들고 있으면 해당 필드만 채운다. arrived는 지오펜스(TRD 1020행)와 원탭 둘 다
    // 이 경로로 들어온다.
    private void applyExecutionSideEffect(PlanRevision revision, Event event, ActionBatchRequest.ActionItem item) {
        Instant now = Instant.now();
        EventExecution execution = eventExecutionRepository.findById(event.getEventId())
                .orElseGet(() -> EventExecution.builder()
                        .eventId(event.getEventId())
                        .finalPlanId(revision.getPlanId())
                        .arrivalResult(ArrivalResult.UNKNOWN.name().toLowerCase())
                        .resultSource("inferred")
                        .createdAt(now)
                        .updatedAt(now)
                        .build());

        switch (item.actionType()) {
            case PREP_STARTED -> execution.setActualPrepStartedAt(item.deviceTs());
            case DEPARTED -> execution.setActualDepartedAt(item.deviceTs());
            case ARRIVED -> {
                execution.setFinalPlanId(revision.getPlanId());
                execution.setActualArrivedAt(item.deviceTs());
                execution.setResultSource(toResultSource(item.actionSource()));
                execution.setArrivalResult(classifyArrival(revision.getTargetArriveAt(), item.deviceTs()));
                planContextRepository.findById(revision.getPlanId())
                        .ifPresent(context -> execution.setActualOutdoorMinutes(context.getEstimatedOutdoorMinutes()));
            }
            default -> {
            }
        }
        execution.setUpdatedAt(now);
        eventExecutionRepository.save(execution);
        recordActionMetric(event, item, execution);

        if (item.actionType() == ActionType.ARRIVED) {
            runPersonalizationAdjustment(event, revision, execution, item);
        }
    }

    private void recordActionMetric(Event event, ActionBatchRequest.ActionItem item, EventExecution execution) {
        switch (item.actionType()) {
            case PREP_STARTED -> productEventService.record(event.getUserId(), "prep_started",
                    Map.of("eventId", event.getEventId().toString()));
            case DEPARTED -> productEventService.record(event.getUserId(), "departed",
                    Map.of("eventId", event.getEventId().toString()));
            case ARRIVED -> productEventService.record(event.getUserId(), "arrival_result", Map.of(
                    "eventId", event.getEventId().toString(), "arrivalResult", execution.getArrivalResult()));
            default -> {
            }
        }
    }

    // TRD §6 원인 분리 EMA 보정 — 도착이 확정된 시점에 계획 대비 실행 데이터를 엔진에 보내
    // 원인과 보정값을 받는다. 학습에서 제외된 이벤트(되돌리기, §15.3)나 기준 추정값이 아직
    // 없는 사용자(콜드 스타트 이전)는 호출 자체를 건너뛴다.
    private void runPersonalizationAdjustment(
            Event event, PlanRevision revision, EventExecution execution, ActionBatchRequest.ActionItem item) {
        if (event.isExcludedFromLearning()) {
            return;
        }
        UserPrepEstimate current = userPrepEstimateRepository.findByUserIdAndValidToIsNull(event.getUserId()).stream()
                .filter(e -> "global".equals(e.getScopeType()))
                .findFirst()
                .orElse(null);
        if (current == null) {
            return;
        }

        Instant now = Instant.now();
        int clockSkewSeconds = (int) Duration.between(item.deviceTs(), now).abs().getSeconds();
        Double seedMinutes = userSettingRepository.findById(event.getUserId())
                .map(UserSetting::getInitialPrepMinutes)
                .map(Integer::doubleValue)
                .orElse(null);

        PersonalizationEngineRequest request = new PersonalizationEngineRequest(
                event.getEventId().toString(),
                new PersonalizationEngineRequest.PlannedExecutionSnapshot(
                        revision.getPrepStartAt(), revision.getRecommendedDepartAt(), revision.getTargetArriveAt(),
                        revision.getEstimatedPrepMinutes(), revision.getTravelMinutes(), revision.getTrafficBufferMinutes()),
                new PersonalizationEngineRequest.ActualExecutionSnapshot(
                        execution.getActualPrepStartedAt(), execution.getActualDepartedAt(), execution.getActualArrivedAt(),
                        execution.getResultSource(), clockSkewSeconds,
                        // 현재 action 계약에는 PREP_FINISHED가 없어 엔진이 prep_finish_unknown으로
                        // degraded 처리한다. 별도 data-capture 이슈에서 실제 시각을 추가한다.
                        null, item.confidence() == null ? null : item.confidence().doubleValue()),
                new PersonalizationEngineRequest.EventOutcome(
                        execution.getArrivalResult(), null, event.isAutoManageExcluded(),
                        // revert는 event.excludedFromLearning gate에서 엔진 호출 자체를 막는다.
                        false, false),
                new PersonalizationEngineRequest.CurrentPrepEstimate(
                        current.getEstimatedMinutes(), current.getSampleCount(),
                        current.getConfidence() != null ? current.getConfidence().doubleValue() : null,
                        current.getModelVersion(), seedMinutes, false),
                new PersonalizationEngineRequest.EngineConfig(
                        PREP_EMA_ALPHA, LATE_WEIGHT, EARLY_WEIGHT, MAX_STEP_MINUTES, COLD_STEP_MINUTES,
                        PREP_FLOOR_MINUTES, PREP_CEILING_RATIO, MODEL_VERSION));

        Optional<PersonalizationEngineResponse> responseOpt = personalizationEngineClient.adjust(request);
        if (responseOpt.isEmpty()) {
            return;
        }
        PersonalizationEngineResponse response = responseOpt.get();
        String cause = response.cause();
        persistDelayReasons(event, response, now);

        if (response.excludedFromLearning() || !"prep_estimate".equals(response.adjustedKnob()) || response.newValue() == null) {
            return;
        }
        current.setValidTo(now);
        userPrepEstimateRepository.save(UserPrepEstimate.builder()
                .userId(event.getUserId())
                .scopeType("global")
                .estimatedMinutes((int) Math.round(response.newValue()))
                .sampleCount(current.getSampleCount() + 1)
                .confidence(current.getConfidence())
                .modelVersion(response.modelVersion())
                .adjustmentReason(response.adjustmentReason())
                .validFrom(now)
                .build());
        productEventService.record(event.getUserId(), "personalization_adjusted", Map.of(
                "eventId", event.getEventId().toString(), "cause", cause));
    }

    private void persistDelayReasons(Event event, PersonalizationEngineResponse response, Instant now) {
        if (response.excludedFromLearning()) {
            return;
        }
        if (response.candidates() == null || response.candidates().isEmpty()) {
            persistDelayReason(event.getEventId(), response.cause(), response.causeConfidence(), now);
            return;
        }
        for (PersonalizationEngineResponse.CauseCandidate candidate : response.candidates()) {
            persistDelayReason(event.getEventId(), candidate.cause(), candidate.confidence(), now);
        }
    }

    // EVENT_DELAY_REASON의 PK는 (event_id, reason_code)다. arrived 재전송처럼 같은 관측이
    // 다시 도착해도 이미 저장된 원인을 덮어쓰지 않아 최초 모델 판단의 재현성을 보존한다.
    private void persistDelayReason(UUID eventId, String cause, Double confidence, Instant now) {
        if (!isPersistableDelayCause(cause)
                || eventDelayReasonRepository.existsById(new EventDelayReasonId(eventId, cause))) {
            return;
        }
        eventDelayReasonRepository.save(EventDelayReason.builder()
                .eventId(eventId)
                .reasonCode(cause)
                .reasonSource("inferred")
                .confidence(confidence == null ? null : BigDecimal.valueOf(confidence))
                .createdAt(now)
                .build());
    }

    private boolean isPersistableDelayCause(String cause) {
        return "prep_late".equals(cause)
                || "prep_overrun".equals(cause)
                || "depart_late".equals(cause)
                || "traffic".equals(cause)
                || "external".equals(cause);
    }

    private String toResultSource(ActionSource actionSource) {
        return switch (actionSource) {
            case USER -> "user";
            case GEO -> "geo";
            case SYSTEM -> "inferred";
        };
    }

    private String classifyArrival(Instant targetArriveAt, Instant actualArrivedAt) {
        long marginMinutes = Duration.between(actualArrivedAt, targetArriveAt).toMinutes();
        if (marginMinutes >= EARLY_THRESHOLD_MINUTES) {
            return ArrivalResult.EARLY.name().toLowerCase();
        }
        if (marginMinutes >= 0) {
            return ArrivalResult.ON_TIME.name().toLowerCase();
        }
        if (marginMinutes >= -LATE_THRESHOLD_MINUTES) {
            return ArrivalResult.RUSHED.name().toLowerCase();
        }
        return ArrivalResult.LATE.name().toLowerCase();
    }

    // planned -> notified -> preparing -> enroute -> arrived -> closed. 역행은 없고,
    // 이미 종료·취소·건너뛴 일정(closed 이후)은 액션으로 다시 열리지 않는다.
    private String advance(String currentStatus, ActionType actionType) {
        EventStatus target = switch (actionType) {
            case PREP_STARTED -> EventStatus.PREPARING;
            case DEPARTED -> EventStatus.ENROUTE;
            case ARRIVED -> EventStatus.ARRIVED;
            default -> null;
        };
        if (target == null) {
            return currentStatus;
        }
        EventStatus current = EventStatus.valueOf(currentStatus.toUpperCase());
        boolean inActivePipeline = current.ordinal() < EventStatus.CLOSED.ordinal();
        return inActivePipeline && target.ordinal() > current.ordinal()
                ? target.name().toLowerCase() : currentStatus;
    }

    private PlanRevision findOwned(UUID userId, UUID planId) {
        PlanRevision revision = planRevisionRepository.findById(planId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));
        eventRepository.findByEventIdAndUserId(revision.getEventId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));
        return revision;
    }
}
