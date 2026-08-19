package com.hq.backend.wellness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WellnessEventSchedulerServiceTest {

    @Mock private WellnessEventGate gate;
    @Mock private WellnessEventScheduleRepository scheduleRepository;
    @Mock private WellnessNotificationPort notificationPort;
    @Mock private PlanWellnessActionRepository actionRepository;
    @Mock private UserWellnessPrefRepository prefRepository;
    @Mock private EventRepository eventRepository;
    @Mock private PlanRevisionRepository planRevisionRepository;

    @Test
    void stop_today는_같은_사용자의_다른_당일_plan_미발송_알림도_취소한다() {
        UUID userId = UUID.randomUUID();
        UUID firstEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();
        UUID firstPlanId = UUID.randomUUID();
        UUID secondPlanId = UUID.randomUUID();
        UUID firstScheduleId = UUID.randomUUID();
        UUID firstNotificationId = UUID.randomUUID();
        UUID secondNotificationId = UUID.randomUUID();
        Instant now = Instant.now();

        Event firstEvent = Event.builder().eventId(firstEventId).userId(userId).startsAt(now).build();
        Event secondEvent = Event.builder().eventId(secondEventId).userId(userId).startsAt(now.plusSeconds(1800)).build();
        PlanRevision firstPlan = PlanRevision.builder().planId(firstPlanId).eventId(firstEventId).build();
        PlanRevision secondPlan = PlanRevision.builder().planId(secondPlanId).eventId(secondEventId).build();
        WellnessEventSchedule first = WellnessEventSchedule.builder()
                .wellnessEventId(firstScheduleId).planId(firstPlanId).notificationId(firstNotificationId)
                .actionCode("sunscreen").scheduledAt(now).sequenceNo((short) 1).build();
        WellnessEventSchedule future = WellnessEventSchedule.builder()
                .wellnessEventId(UUID.randomUUID()).planId(secondPlanId).notificationId(secondNotificationId)
                .actionCode("sunscreen").scheduledAt(now.plusSeconds(1800)).sequenceNo((short) 1).build();

        when(scheduleRepository.findById(firstScheduleId)).thenReturn(Optional.of(first));
        when(planRevisionRepository.findById(firstPlanId)).thenReturn(Optional.of(firstPlan));
        when(eventRepository.findById(firstEventId)).thenReturn(Optional.of(firstEvent));
        when(eventRepository.findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(firstEvent, secondEvent));
        when(planRevisionRepository.findByEventIdIn(List.of(firstEventId, secondEventId)))
                .thenReturn(List.of(firstPlan, secondPlan));
        when(scheduleRepository.findByPlanIdIn(List.of(firstPlanId, secondPlanId)))
                .thenReturn(List.of(first, future));

        scheduler().handleResponse(firstScheduleId, "stop_today", userId);

        assertThat(future.getCancelledAt()).isNotNull();
        assertThat(future.getCancelReason()).isEqualTo("user_stop_today");
        verify(notificationPort).cancelWellnessNotification(secondNotificationId);
    }

    private WellnessEventSchedulerService scheduler() {
        return new WellnessEventSchedulerService(
                gate, scheduleRepository, notificationPort, actionRepository, prefRepository,
                eventRepository, planRevisionRepository);
    }
}
