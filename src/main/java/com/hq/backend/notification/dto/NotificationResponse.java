package com.hq.backend.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID notificationId,
        UUID planId,
        String notificationCategory,
        String notificationType,
        String slot,
        Instant scheduledAt,
        Instant sentAt,
        String deliveryStatus,
        String body,
        String triggerReason,
        String reaction
) {
}
