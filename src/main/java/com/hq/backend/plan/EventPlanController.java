package com.hq.backend.plan;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.plan.dto.PlanDetailResponse;
import com.hq.backend.plan.dto.PlanRecalculateResponse;
import com.hq.backend.plan.dto.RecalculateRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events/{eventId}")
@RequiredArgsConstructor
public class EventPlanController {

    private final PlanService planService;

    @GetMapping("/plans/latest")
    public PlanDetailResponse latest(@CurrentUserId UUID userId, @PathVariable UUID eventId) {
        return planService.getLatestForEvent(userId, eventId);
    }

    @PostMapping("/plan/recalculate")
    public PlanRecalculateResponse recalculate(
            @CurrentUserId UUID userId, @PathVariable UUID eventId,
            @RequestBody(required = false) RecalculateRequest request) {
        return planService.recalculate(userId, eventId);
    }
}
