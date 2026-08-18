package com.hq.backend.plan;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.ActionSource;
import com.hq.backend.event.ActionType;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventActionLog;
import com.hq.backend.event.EventActionLogRepository;
import com.hq.backend.event.EventRepository;
import com.hq.backend.event.EventStatus;
import com.hq.backend.personalization.ArrivalResult;
import com.hq.backend.personalization.EventExecution;
import com.hq.backend.personalization.EventExecutionRepository;
import com.hq.backend.plan.dto.ActionBatchRequest;
import com.hq.backend.plan.dto.ActionBatchResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
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

    private static final Set<ActionType> EXECUTION_RELEVANT_ACTIONS =
            EnumSet.of(ActionType.PREP_STARTED, ActionType.DEPARTED, ActionType.ARRIVED);

    private final PlanRevisionRepository planRevisionRepository;
    private final EventRepository eventRepository;
    private final EventActionLogRepository eventActionLogRepository;
    private final EventExecutionRepository eventExecutionRepository;
    private final PlanContextRepository planContextRepository;
    private final PlanService planService;

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
