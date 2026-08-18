package com.hq.backend.notification;

import com.hq.backend.pushdevice.PushDevice;
import com.hq.backend.pushdevice.PushDeviceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * TRD §13.1 아웃박스 발송 워커.
 * notification_outbox에서 pending 행을 폴링해 FCM으로 발송하고,
 * 성공 시 notification.sent_at·delivery_status를 갱신한다.
 *
 * 실패 시 retryCount 증가, 3회 초과하면 dead → notification.delivery_status=failed.
 * 5분 백오프 3회(§13.3).
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationRepository notificationRepository;
    private final PushDeviceRepository pushDeviceRepository;
    private final FcmSender fcmSender;

    public NotificationDispatcher(NotificationOutboxRepository outboxRepository,
                                  NotificationRepository notificationRepository,
                                  PushDeviceRepository pushDeviceRepository,
                                  FcmSender fcmSender) {
        this.outboxRepository = outboxRepository;
        this.notificationRepository = notificationRepository;
        this.pushDeviceRepository = pushDeviceRepository;
        this.fcmSender = fcmSender;
    }

    /** 10초마다 아웃박스에서 발송 대기 행을 처리 */
    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void dispatchPending() {
        List<NotificationOutbox> pending = outboxRepository.findPendingOrderByCreatedAt();
        if (pending.isEmpty()) {
            return;
        }

        log.debug("[Dispatcher] {} 건 발송 시작", pending.size());

        for (NotificationOutbox outbox : pending) {
            try {
                dispatch(outbox);
            } catch (Exception e) {
                log.error("[Dispatcher] outbox_id={} 발송 실패", outbox.getOutboxId(), e);
                outbox.incrementRetry();
                if ("dead".equals(outbox.getStatus())) {
                    markNotificationFailed(outbox.getNotificationId());
                }
            }
        }
    }

    private void dispatch(NotificationOutbox outbox) {
        // 발송 전 notification 상태 재확인 (상태 입력과 교차 시 취소된 건 스킵)
        Optional<Notification> notifOpt = notificationRepository.findById(outbox.getNotificationId());
        if (notifOpt.isEmpty()) {
            outbox.setStatus("done");
            outbox.setProcessedAt(Instant.now());
            return;
        }

        Notification notification = notifOpt.get();
        if ("cancelled".equals(notification.getDeliveryStatus())) {
            outbox.setStatus("done");
            outbox.setProcessedAt(Instant.now());
            log.debug("[Dispatcher] notification_id={} 이미 취소됨, 스킵", notification.getNotificationId());
            return;
        }

        // 대상 FCM 토큰 조회 (active 상태만)
        List<String> tokens = pushDeviceRepository.findByUserIdAndTokenStatus(outbox.getUserId(), "active")
                .stream()
                .map(PushDevice::getCurrentToken)
                .toList();

        if (tokens.isEmpty()) {
            log.warn("[Dispatcher] user_id={} 등록된 푸시 기기 없음", outbox.getUserId());
            outbox.setStatus("done");
            outbox.setProcessedAt(Instant.now());
            notification.setDeliveryStatus("failed");
            notification.setSentAt(Instant.now());
            return;
        }

        // FCM 발송
        int sent = fcmSender.send(
                tokens,
                "Ensom",
                notification.getBodyMasked(),
                outbox.getCollapseKey(),
                Map.of(
                        "notification_id", notification.getNotificationId().toString(),
                        "type", notification.getNotificationType(),
                        "plan_id", notification.getPlanId().toString()
                )
        );

        if (sent > 0) {
            notification.setDeliveryStatus("sent");
            notification.setSentAt(Instant.now());
            outbox.setStatus("done");
            outbox.setProcessedAt(Instant.now());
            log.info("[Dispatcher] 발송 완료: notification_id={}, tokens={}",
                    notification.getNotificationId(), sent);
        } else {
            outbox.incrementRetry();
            if ("dead".equals(outbox.getStatus())) {
                markNotificationFailed(outbox.getNotificationId());
            }
        }
    }

    private void markNotificationFailed(UUID notificationId) {
        notificationRepository.findById(notificationId)
                .ifPresent(n -> {
                    n.setDeliveryStatus("failed");
                    log.warn("[Dispatcher] notification_id={} 최종 실패 처리", notificationId);
                });
    }
}
