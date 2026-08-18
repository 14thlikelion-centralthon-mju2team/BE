package com.hq.backend.calendar.dto;

// 제목·설명 등 원문은 절대 매핑하지 않는다 — TRD 6.4/6.8 "빈 시간대만 추출, 원문 미저장" 원칙.
public record GoogleCalendarEvent(String id, String status, GoogleEventDateTime start, GoogleEventDateTime end) {
}
