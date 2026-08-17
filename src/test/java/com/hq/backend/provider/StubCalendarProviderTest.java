package com.hq.backend.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StubCalendarProviderTest {

    private final CalendarProvider provider = new StubCalendarProvider();

    @Test
    void syncReturnsOneConfirmedEvent() {
        Instant since = Instant.parse("2026-08-17T00:00:00Z");

        List<CalendarEvent> events = provider.sync("any-source", since);

        assertThat(events).hasSize(1);
        CalendarEvent event = events.get(0);
        assertThat(event.status()).isEqualTo("confirmed");
        assertThat(event.startsAt()).isAfter(since);
        assertThat(event.endsAt()).isAfter(event.startsAt());
    }
}
