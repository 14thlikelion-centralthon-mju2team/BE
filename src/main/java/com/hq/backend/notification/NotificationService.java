package com.hq.backend.notification;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.pushdevice.PushDevice;
import com.hq.backend.pushdevice.PushDeviceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 발송 및 취소를 담당하는 서비스.
 *
 * TRD §13.1 — notification INSERT → 커밋 → FCM 발송.
 * 아웃박스 패턴은 notification_outbox DDL 추가 후 활성화 예정.
 * 현재는 발송 시각 도래 시 즉시 FCM을 호출하는 단순 구조.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final EventRepository eventRepository;
    private final PushDeviceRepository pushDeviceRepository;
    private final FcmSender fcmSender;

    public NotificationService(NotificationRepository notificationRepository,
                               EventRepository eventRepository,
                               PushDeviceRepository pushDeviceRepository,
                               FcmSender fcmSender) {
        this.notificationRepository = notificationRepository;
        this.eventRepository = eventRepository;
        this.pushDeviceRepository = pushDeviceRepository;
        this.fcmSender = fcmSender;
    }

    /**
     * 발송 시각이 도래한 알림을 즉시 FCM으로 발송한다.
     */
    @Transactional
    public void sendNotification(Notification notification, PlanRevision revision) {
        UUID eventId = revision.getEventId();
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("event not found: " + eventId));

        List<String> tokens = pushDeviceRepository.findByUserIdAndTokenStatus(event.getUserId(), "active")
                .stream()
                .map(PushDevice::getCurrentToken)
                .toList();

        if (tokens.isEmpty()) {
            log.warn("[NotificationService] user_id={} 등록된 푸시 기기 없음", event.getUserId());
            notification.setDeliveryStatus("failed");
            notification.setSentAt(Instant.now());
            return;
        }

        String collapseKey = eventId + ":" + notification.getNotificationType();
        int sent = fcmSender.send(tokens, "Ensom", notification.getBodyMasked(), collapseKey,
                Map.of("notification_id", notification.getNotificationId().toString(),
                        "type", notification.getNotificationType(),
                        "plan_id", notification.getPlanId().toString()));

        if (sent > 0) {
            notification.setDeliveryStatus("sent");
            notification.setSentAt(Instant.now());
            log.info("[NotificationService] 발송 완료: notification_id={}", notification.getNotificationId());
        } else {
            notification.setDeliveryStatus("failed");
            notification.setSentAt(Instant.now());
        }
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
        if (cancelled > 0) {
            log.info("[NotificationService] plan_id={} 예약 알림 {}건 취소", planId, cancelled);
        }
        return cancelled;
    }
}
