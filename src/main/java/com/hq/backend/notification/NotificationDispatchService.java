package com.hq.backend.notification;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * FCM 호출은 transaction 밖에서 수행한다. DB 상태 조회·결과 기록은
 * NotificationDispatchState의 독립된 짧은 transaction으로 분리한다.
 */
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

    private final NotificationDispatchState dispatchState;
    private final FcmSender fcmSender;

    public void dispatchScheduledNotification(java.util.UUID notificationId) {
        dispatchState.prepare(notificationId).ifPresent(command -> {
            if (command.tokens().isEmpty()) {
                log.warn("[NotificationDispatch] event_id={} 등록된 푸시 기기 없음", command.eventId());
                dispatchState.complete(command, 0, Instant.now());
                return;
            }

            int sent = fcmSender.send(
                    command.tokens(),
                    "Ensom",
                    command.bodyMasked(),
                    command.eventId() + ":" + command.notificationType(),
                    Map.of(
                            "notification_id", command.notificationId().toString(),
                            "type", command.notificationType(),
                            "plan_id", command.planId().toString()));

            if (dispatchState.complete(command, sent, Instant.now())) {
                log.info("[NotificationDispatch] notification_id={} result={}",
                        command.notificationId(), sent > 0 ? "sent" : "failed");
            }
        });
    }
}
