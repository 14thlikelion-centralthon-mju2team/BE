package com.hq.backend.notification;

import java.util.UUID;

/**
 * 일정 상태가 전이되었을 때 발행하는 도메인 이벤트.
 * PlanActionService에서 publish하면 NotificationCanceller가 수신해 남은 알림을 취소한다.
 *
 * TRD §8.1: "상태 입력 시 남은 슬롯 소각"
 */
public record PlanStatusChangedEvent(
        UUID planId,
        UUID eventId,
        String previousStatus,
        String newStatus
) {
}
