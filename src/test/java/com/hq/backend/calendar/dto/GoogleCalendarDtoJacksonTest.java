package com.hq.backend.calendar.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class GoogleCalendarDtoJacksonTest {

    @Autowired private tools.jackson.databind.ObjectMapper objectMapper;

    @Test
    void busy_응답은_제목을_매핑하지_않고_sync_응답만_제목을_매핑한다() throws Exception {
        String json = """
                {"items":[{"id":"event-1","status":"confirmed","summary":"온라인 회의",\
                "start":{"dateTime":"2026-08-20T01:00:00Z"},\
                "end":{"dateTime":"2026-08-20T02:00:00Z"}}]}
                """;

        GoogleBusyEventsResponse busy = objectMapper.readValue(json, GoogleBusyEventsResponse.class);
        GoogleCalendarSyncEventsResponse sync = objectMapper.readValue(json, GoogleCalendarSyncEventsResponse.class);

        assertThat(busy.items()).singleElement().satisfies(event -> {
            assertThat(event.start().dateTime()).isEqualTo(Instant.parse("2026-08-20T01:00:00Z"));
            assertThat(event.getClass().getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("summary");
        });
        assertThat(sync.items()).singleElement().extracting(GoogleCalendarSyncEvent::summary)
                .isEqualTo("온라인 회의");
    }
}
