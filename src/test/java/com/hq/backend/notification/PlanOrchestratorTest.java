package com.hq.backend.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hq.backend.plan.PlanRevision;
import com.hq.backend.wellness.WellnessEventSchedulerService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PlanOrchestratorTest {

    @Mock private PlanEvalRepository planEvalRepository;
    @Mock private NotificationScheduler notificationScheduler;
    @Mock private NotificationRepository notificationRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private WellnessEventSchedulerService wellnessEventSchedulerService;

    @Test
    void due_notification은_FCM대신_commit후_dispatch_event로_위임한다() {
        Instant now = Instant.now();
        UUID planId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        PlanRevision revision = PlanRevision.builder()
                .planId(planId)
                .eventId(UUID.randomUUID())
                .prepStartAt(now.plusSeconds(1200))
                .recommendedDepartAt(now.plusSeconds(1800))
                .targetArriveAt(now.plusSeconds(2400))
                .build();
        Notification due = Notification.builder()
                .notificationId(notificationId)
                .planId(planId)
                .scheduledAt(now.minusSeconds(1))
                .deliveryStatus("scheduled")
                .build();
        when(planEvalRepository.findDueForEvaluation(any(), anyInt())).thenReturn(List.of(revision));
        when(notificationRepository.findByPlanIdAndDeliveryStatus(planId, "scheduled")).thenReturn(List.of(due));

        orchestrator().tick();

        ArgumentCaptor<NotificationDueEvent> event = ArgumentCaptor.forClass(NotificationDueEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        org.assertj.core.api.Assertions.assertThat(event.getValue().notificationId()).isEqualTo(notificationId);
    }

    private PlanOrchestrator orchestrator() {
        return new PlanOrchestrator(
                planEvalRepository,
                notificationScheduler,
                notificationRepository,
                eventPublisher,
                wellnessEventSchedulerService);
    }
}
