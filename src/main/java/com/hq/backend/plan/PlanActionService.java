package com.hq.backend.plan;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventActionLog;
import com.hq.backend.event.EventActionLogRepository;
import com.hq.backend.event.EventRepository;
import com.hq.backend.event.EventStatus;
import com.hq.backend.plan.dto.ActionBatchRequest;
import com.hq.backend.plan.dto.ActionBatchResponse;
import java.time.Duration;
import java.time.Instant;
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

    private final PlanRevisionRepository planRevisionRepository;
    private final EventRepository eventRepository;
    private final EventActionLogRepository eventActionLogRepository;
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

            status = advance(status, item.actionType());
        }

        if (!status.equals(event.getStatus())) {
            event.setStatus(status);
        }

        return new ActionBatchResponse(accepted, duplicated, status, planService.getLatestForEvent(userId, event.getEventId()));
    }

    // planned -> notified -> preparing -> enroute -> arrived -> closed. 역행은 없고,
    // 이미 종료·취소·건너뛴 일정(closed 이후)은 액션으로 다시 열리지 않는다.
    private String advance(String currentStatus, com.hq.backend.event.ActionType actionType) {
        EventStatus target = switch (actionType) {
            case PREP_STARTED -> EventStatus.PREPARING;
            case DEPARTED -> EventStatus.ENROUTE;
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
