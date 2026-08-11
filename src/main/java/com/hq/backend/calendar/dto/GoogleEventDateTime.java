package com.hq.backend.calendar.dto;

import java.time.Instant;

// 종일 일정은 date만 오고 dateTime이 없다 — ponytail: 종일 일정은 이번 스코프에서 미지원,
// dateTime 없는 항목은 CalendarService에서 건너뛴다. date 필드는 안 받는다.
public record GoogleEventDateTime(Instant dateTime) {
}
