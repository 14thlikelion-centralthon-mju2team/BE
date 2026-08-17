package com.hq.backend.provider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

// ponytail: Google Calendar 실 연동(M1) 전까지 쓰는 고정값 스텁. 입력 무관하게 확정 일정 1건을 반환한다.
// 실 구현으로 교체할 때는 이 클래스를 지우고 GoogleCalendarProvider로 빈을 바꾸면 끝.
@Component
public class StubCalendarProvider implements CalendarProvider {
    @Override
    public List<CalendarEvent> sync(String calendarSourceId, Instant since) {
        CalendarEvent event = new CalendarEvent(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                since.plusSeconds(3600),
                since.plusSeconds(7200),
                "stub destination",
                37.5665,
                126.9780,
                "confirmed",
                "stub"
        );
        return List.of(event);
    }
}
