package com.hq.backend.plan;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.plan.dto.ActionBatchRequest;
import com.hq.backend.plan.dto.ActionBatchResponse;
import com.hq.backend.plan.dto.PlanDetailResponse;
import com.hq.backend.plan.dto.PlanPatchRequest;
import com.hq.backend.plan.dto.RouteOptionResponse;
import com.hq.backend.plan.dto.RouteSelectRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/plans/{planId}")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;
    private final PlanActionService planActionService;

    @GetMapping
    public PlanDetailResponse get(@CurrentUserId UUID userId, @PathVariable UUID planId) {
        return planService.get(userId, planId);
    }

    @PatchMapping
    public PlanDetailResponse patch(
            @CurrentUserId UUID userId, @PathVariable UUID planId, @RequestBody PlanPatchRequest request) {
        return planService.patch(userId, planId, request);
    }

    @GetMapping("/routes")
    public List<RouteOptionResponse> routes(@CurrentUserId UUID userId, @PathVariable UUID planId) {
        return planService.listRoutes(userId, planId);
    }

    @PostMapping("/routes/select")
    public PlanDetailResponse selectRoute(
            @CurrentUserId UUID userId, @PathVariable UUID planId, @Valid @RequestBody RouteSelectRequest request) {
        return planService.selectRoute(userId, planId, request.routeOptionId());
    }

    @PostMapping("/actions")
    public ActionBatchResponse submitActions(
            @CurrentUserId UUID userId, @PathVariable UUID planId, @Valid @RequestBody ActionBatchRequest request) {
        return planActionService.submit(userId, planId, request);
    }
}
