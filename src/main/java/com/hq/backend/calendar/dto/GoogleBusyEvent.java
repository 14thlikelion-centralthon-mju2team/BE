package com.hq.backend.calendar.dto;

// 밀도 조회에는 일정 제목을 받을 이유가 없다. Google fields와 함께 이 DTO도 시간만 표현한다.
public record GoogleBusyEvent(GoogleEventDateTime start, GoogleEventDateTime end) {
}
