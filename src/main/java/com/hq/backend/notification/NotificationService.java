package com.hq.backend.notification;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanRevision;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 취소 및 아웃박스 투입을 담당하는 서비스.
 *
 * TRD §13.1 — notification INSERT → 커밋 → 아웃박스 → FCM 발송.
 * 상태 입력 시 남은 예약 알림을 취소하고 일정 상태를 원자적으로 전이한다.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationOutboxRepository outboxRepository;
    private final EventRepository eventRepository;

    public NotificationService(NotificationRepository notificationRepository,
                               NotificationOutboxRepository outboxRepository,
                               EventRepository eventRepository) {
        this.notificationRepository = notificationRepository;
        this.outboxRepository = outboxRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * 예약된 알림의 발송 시각이 도래했을 때 아웃박스에 투입한다.
     * PlanOrchestrator에서 scheduled_at <= now인 알림을 찾아 호출.
     */
    @Transactional
    public void enqueueForDelivery(Notification notification, PlanRevision revision) {
        UUID eventId = revision.getEventId();
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("event not found: " + eventId));

        String collapseKey = eventId + ":" + notification.getNotificationType();

        NotificationOutbox outbox = NotificationOutbox.builder()
                .notificationId(notification.getNotificationId())
                .userId(event.getUserId())
                .collapseKey(collapseKey)
                .payload(notification.getBodyMasked())
                .createdAt(Instant.now())
                .build();

        outboxRepository.save(outbox);
        log.debug("[NotificationService] 아웃박스 투입: notification_id={}, user_id={}",
                notification.getNotificationId(), event.getUserId());
    }

    /**
     * 상태 입력(행동 기록) 시 해당 계획의 남은 예약 알림을 모두 취소한다.
     * TRD §8.1 "상태 입력 시 남은 슬롯 소각"
     *
     * @return 취소된 알림 수
     */
    @Transactional
    public int cancelPendingNotifications(UUID planId) {
        int cancelled = notificationRepository.cancelPendingByPlanId(planId);

        // 아웃박스에서도 pending 상태인 것을 done으로 마킹
        notificationRepository.findByPlanIdAndDeliveryStatus(planId, "cancelled")
                .forEach(n -> outboxRepository.findByNotificationIdAndStatus(n.getNotificationId(), "pending")
                        .forEach(o -> {
                            o.setStatus("done");
                            o.setProcessedAt(Instant.now());
                        }));

        if (cancelled > 0) {
            log.info("[NotificationService] plan_id={} 예약 알림 {}건 취소", planId, cancelled);
        }
        return cancelled;
    }
}
