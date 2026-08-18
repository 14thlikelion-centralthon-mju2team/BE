package com.hq.backend.wellness;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.wellness.dto.WellnessActionResolveRequest;
import com.hq.backend.wellness.dto.WellnessActionResolveResponse;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// API 명세 §12.2 — PLAN_WELLNESS_ACTION.completion_status. prep-items resolve와 테이블·
// 상태 enum이 달라 별도 엔드포인트로 분리돼 있다(완료율 지표의 분모가 다름, PRD §24.4).
@Service
@RequiredArgsConstructor
public class WellnessActionResolveService {

    private final PlanRevisionRepository planRevisionRepository;
    private final EventRepository eventRepository;
    private final PlanWellnessActionRepository planWellnessActionRepository;

    @Transactional
    public WellnessActionResolveResponse resolve(
            UUID userId, UUID planId, UUID wellnessActionId, WellnessActionResolveRequest request) {
        PlanRevision revision = planRevisionRepository.findById(planId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));
        eventRepository.findByEventIdAndUserId(revision.getEventId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));

        PlanWellnessAction action = planWellnessActionRepository.findByWellnessActionIdAndPlanId(wellnessActionId, planId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "WELLNESS_ACTION_NOT_FOUND", "웰니스 행동을 찾을 수 없습니다."));

        action.setCompletionStatus(request.completionStatus().name().toLowerCase());
        action.setRespondedAt(Instant.now());

        return WellnessActionResolveResponse.from(action);
    }
}
