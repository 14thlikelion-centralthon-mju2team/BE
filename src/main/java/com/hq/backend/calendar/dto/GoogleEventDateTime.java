package com.hq.backend.calendar.dto;

import java.time.Instant;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

// Google Calendar API는 OAuth 토큰 엔드포인트(snake_case)와 달리 자체 JSON 스타일(camelCase)을
// 쓴다. 프로젝트 전역 설정(spring.jackson.property-naming-strategy: SNAKE_CASE)을 그대로 두면
// "dateTime"을 "date_time"으로 찾아서 항상 null이 된다 — 이 DTO만 카멜케이스로 오버라이드.
//
// 종일 일정은 date만 오고 dateTime이 없다 — ponytail: 종일 일정은 이번 스코프에서 미지원,
// dateTime 없는 항목은 CalendarService에서 건너뛴다. date 필드는 안 받는다.
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record GoogleEventDateTime(Instant dateTime) {
}
