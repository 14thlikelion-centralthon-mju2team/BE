package com.hq.backend.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID notificationId,
        UUID planId,
        String notificationCategory,
        String notificationType,
        Instant scheduledAt,
        Instant sentAt,
        String deliveryStatus,
        String bodyMasked,
        String triggerReason
) {
}
