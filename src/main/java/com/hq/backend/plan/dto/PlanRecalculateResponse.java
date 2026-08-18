package com.hq.backend.plan.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

// API 명세 §9.4 — changed와 계획 상세 필드가 같은 레벨에 평평하게 나온다.
public record PlanRecalculateResponse(boolean changed, @JsonUnwrapped PlanDetailResponse plan) {
}
