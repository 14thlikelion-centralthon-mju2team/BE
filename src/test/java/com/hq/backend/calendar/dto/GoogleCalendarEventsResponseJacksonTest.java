package com.hq.backend.calendar.dto;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 구글 캘린더 API가 실제로 내려주는 원본 JSON(camelCase)이, 프로젝트 전역
// spring.jackson.property-naming-strategy: SNAKE_CASE 설정 하에서 우리 DTO로 제대로 파싱되는지
// 확인 — CalendarService.fetchGoogleBusyBlocks()가 실제로 이 응답을 소비한다.
@SpringBootTest
class GoogleCalendarEventsResponseJacksonTest {

    @Autowired private ObjectMapper objectMapper;

    @Test
    void 구글이_실제로_보내는_camelCase_JSON을_파싱한다() throws Exception {
        String googleActualJson = """
                {
                  "items": [
                    {
                      "start": {"dateTime": "2026-08-14T09:00:00+09:00"},
                      "end": {"dateTime": "2026-08-14T10:00:00+09:00"}
                    }
                  ]
                }
                """;

        GoogleCalendarEventsResponse result = objectMapper.readValue(googleActualJson, GoogleCalendarEventsResponse.class);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).start().dateTime()).isNotNull();
    }
}
