package com.hq.backend.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hq.backend.calendar.dto.CalendarConnectionStatusResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarControllerTest {

    @Mock private CalendarService calendarService;
    @Mock private CalendarSyncService calendarSyncService;

    private CalendarController controller;

    @BeforeEach
    void setUp() {
        controller = new CalendarController(calendarService, calendarSyncService);
    }

    @Test
    void google_status는_service의_연결_상태를_반환한다() {
        UUID userId = UUID.randomUUID();
        when(calendarService.getGoogleConnectionStatus(userId))
                .thenReturn(new CalendarConnectionStatusResponse(true));

        var response = controller.googleConnectionStatus(userId);

        assertThat(response.connected()).isTrue();
        verify(calendarService).getGoogleConnectionStatus(userId);
    }

    @Test
    void manual_sync는_no_AI_syncForUser에만_위임한다() {
        UUID userId = UUID.randomUUID();

        controller.sync(userId);

        verify(calendarSyncService).syncForUser(userId);
    }
}
