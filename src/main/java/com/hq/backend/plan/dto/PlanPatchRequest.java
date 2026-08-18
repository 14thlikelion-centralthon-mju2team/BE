package com.hq.backend.plan.dto;

import java.time.Instant;
import java.util.UUID;

// API 명세 §9.5 — 사용자 직접 수정(출발지·시각). 둘 다 선택 사항, 준 것만 반영한다.
public record PlanPatchRequest(UUID originPlaceId, Instant prepStartAt) {
}
