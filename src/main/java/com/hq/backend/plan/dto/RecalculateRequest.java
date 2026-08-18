package com.hq.backend.plan.dto;

// API 명세 §9.4 — reason은 클라이언트 기록용이라 서버에 저장 컬럼이 없다. 수신만 하고 무시한다.
public record RecalculateRequest(String reason) {
}
