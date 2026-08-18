package com.hq.backend.plan.dto;

public record ActionBatchResponse(int accepted, int duplicated, String eventStatus, PlanDetailResponse plan) {
}
