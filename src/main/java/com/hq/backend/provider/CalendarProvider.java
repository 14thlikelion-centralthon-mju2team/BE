package com.hq.backend.provider;

import java.time.Instant;
import java.util.List;

// TRD 11.3. Google Calendar 우선(D2의 IdP와 OAuth 동의 통합). 실 연동은 M1, M0은 계약만 고정한다.
// 부재 시 동작(TRD 11.5): 마지막 스냅샷 유지 + 지수 백오프, 내부 생성 일정은 영향 없음.
public interface CalendarProvider {
    List<CalendarEvent> sync(String calendarSourceId, Instant since);
}
