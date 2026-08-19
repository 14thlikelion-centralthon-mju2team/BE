package com.hq.backend.wellness;

import com.hq.backend.notification.Notification;
import com.hq.backend.notification.NotificationRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Bridges wellness scheduling to the shared notification outbox. */
@Component
public class StubWellnessNotificationPort implements WellnessNotificationPort {
    private final NotificationRepository notificationRepository;

    public StubWellnessNotificationPort(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Override
    public boolean existsByDedupKey(String dedupKey) {
        return notificationRepository.findByDedupKey(dedupKey).isPresent();
    }

    @Override
    public UUID createWellnessNotification(UUID planId, Instant scheduledAt,
                                           String bodyMasked, String triggerReason, String dedupKey) {
        return notificationRepository.save(Notification.builder()
                .planId(planId)
                .notificationCategory("wellness")
                .notificationType("wellness_event")
                .scheduledAt(scheduledAt)
                .deliveryStatus("scheduled")
                .bodyMasked(bodyMasked)
                .triggerReason(triggerReason)
                .dedupKey(dedupKey)
                .build()).getNotificationId();
    }
}
