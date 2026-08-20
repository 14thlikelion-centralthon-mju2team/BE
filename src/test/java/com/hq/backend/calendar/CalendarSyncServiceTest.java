package com.hq.backend.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hq.backend.calendar.dto.GoogleCalendarEvent;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanCreationService;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

class CalendarSyncServiceTest {

    private final CalendarConnectionRepository connectionRepository = mock(CalendarConnectionRepository.class);
    private final CalendarSourceRepository calendarSourceRepository = mock(CalendarSourceRepository.class);
    private final EventRepository eventRepository = mock(EventRepository.class);
    private final PlanCreationService planCreationService = mock(PlanCreationService.class);
    private final PlanRevisionRepository planRevisionRepository = mock(PlanRevisionRepository.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final CalendarSyncService service = new CalendarSyncService(
            connectionRepository,
            calendarSourceRepository,
            eventRepository,
            planCreationService,
            planRevisionRepository,
            mock(BytesEncryptor.class),
            mock(RestClient.class),
            transactionTemplate);

    @Test
    void recompute이_예외없이_빈결과를_반환하면_기존_active_plan을_복원한다() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID originPlaceId = UUID.randomUUID();
        Event event = Event.builder()
                .eventId(eventId)
                .userId(userId)
                .startsAt(Instant.parse("2026-08-21T03:00:00Z"))
                .build();
        PlanRevision activePlan = PlanRevision.builder()
                .eventId(eventId)
                .originPlaceId(originPlaceId)
                .revisionNo(1)
                .inputHash("previous-input")
                .planStatus("active")
                .build();

        when(planRevisionRepository.findByEventIdAndPlanStatus(eventId, "active"))
                .thenReturn(Optional.of(activePlan));
        when(planCreationService.recompute(
                eq(userId), eq(event), eq(originPlaceId), eq(2), eq("previous-input"), any()))
                .thenReturn(new PlanCreationService.RecomputeResult(Optional.empty(), false));

        service.triggerRecalculate(userId, event);

        assertThat(activePlan.getPlanStatus()).isEqualTo("active");
        verify(planRevisionRepository, times(2)).saveAndFlush(activePlan);
    }

    @Test
    void event_persist_실패시_syncToken을_전진시키지_않는다() {
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        GoogleCalendarEvent failedEvent = new GoogleCalendarEvent("google-event", "confirmed", null, null);

        doThrow(new IllegalStateException("event persistence failed"))
                .when(transactionTemplate)
                .execute(any());

        service.processEventsAndAdvanceSyncToken(
                userId, connectionId, List.of(failedEvent), "next-sync-token");

        verify(transactionTemplate).execute(any());
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }
}
