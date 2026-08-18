package com.hq.backend.notification.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 알림 응답 요청.
 * reaction: "started" | "snoozed" | "dismissed" | "departed"
 */
public record NotificationRespondRequest(
        @NotBlank String reaction
) {
}
