package com.hq.backend.plan;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.dto.PrepItemResolveRequest;
import com.hq.backend.plan.dto.PrepItemResolveResponse;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// API 명세 §12.2 — PLAN_PREP_ITEM.completion_status.
@Service
@RequiredArgsConstructor
public class PlanPrepItemResolveService {

    private final PlanRevisionRepository planRevisionRepository;
    private final EventRepository eventRepository;
    private final PlanPrepItemRepository planPrepItemRepository;

    @Transactional
    public PrepItemResolveResponse resolve(UUID userId, UUID planId, UUID planPrepItemId, PrepItemResolveRequest request) {
        PlanRevision revision = planRevisionRepository.findById(planId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));
        eventRepository.findByEventIdAndUserId(revision.getEventId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));

        PlanPrepItem item = planPrepItemRepository.findByPlanPrepItemIdAndPlanId(planPrepItemId, planId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PREP_ITEM_NOT_FOUND", "준비 항목을 찾을 수 없습니다."));

        String status = request.completionStatus().name().toLowerCase();
        item.setCompletionStatus(status);
        item.setCompletedAt("completed".equals(status) ? Instant.now() : null);

        return PrepItemResolveResponse.from(item);
    }
}
