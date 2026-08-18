package com.hq.backend.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * TRD §8.1 — 상태 입력 시 남은 예약 알림 소각.
 * PlanActionService가 ApplicationEventPublisher.publishEvent(PlanStatusChangedEvent)를
 * 호출하면 이 리스너가 자동으로 수신한다.
 *
 * 김민형 코드(PlanActionService)에 publishEvent 한 줄 추가만 요청하면 연동 완료.
 */
@Component
public class NotificationCanceller {

    private static final Logger log = LoggerFactory.getLogger(NotificationCanceller.class);

    private final NotificationService notificationService;

    public NotificationCanceller(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 상태가 PREPARING 이상으로 진행되면 남은 예약 알림을 모두 취소한다.
     * ARRIVED/CLOSED로 전이될 때도 혹시 남은 알림이 있으면 정리.
     */
    @EventListener
    public void onStatusChanged(PlanStatusChangedEvent event) {
        String newStatus = event.newStatus();
        // preparing, enroute, arrived, closed 진입 시 남은 scheduled 알림 취소
        if ("preparing".equals(newStatus) || "enroute".equals(newStatus)
                || "arrived".equals(newStatus) || "closed".equals(newStatus)) {
            int cancelled = notificationService.cancelPendingNotifications(event.planId());
            if (cancelled > 0) {
                log.info("[NotificationCanceller] plan_id={} 상태={}로 전이, {}건 알림 취소",
                        event.planId(), newStatus, cancelled);
            }
        }
    }
}
