package com.hq.backend.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hq.backend.plan.PlanRevision;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationSchedulerTest {

    @Mock private NotificationRepository notificationRepository;

    @Test
    void 동일_dedup_key를_다른_워커가_먼저_예약하면_예외_없이_스킵한다() {
        UUID planId = UUID.randomUUID();
        PlanRevision revision = PlanRevision.builder()
                .planId(planId)
                .eventId(UUID.randomUUID())
                .revisionNo(1)
                .prepStartAt(Instant.now().plusSeconds(3600))
                .build();
        when(notificationRepository.countTimeNotificationsByPlanId(planId)).thenReturn(0);
        when(notificationRepository.insertIfAbsent(
                any(), anyString(), anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(0);

        new NotificationScheduler(notificationRepository).scheduleTimeSlots(revision, Instant.now());

        verify(notificationRepository, times(2)).insertIfAbsent(
                any(), anyString(), anyString(), any(), anyString(), anyString(), anyString());
        verify(notificationRepository, never()).save(any());
    }
}
