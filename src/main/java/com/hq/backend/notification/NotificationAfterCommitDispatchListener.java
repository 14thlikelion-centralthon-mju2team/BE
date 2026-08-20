package com.hq.backend.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 계획 평가 DB transaction이 성공적으로 commit된 뒤 FCM dispatch를 시작한다. */
@Component
@RequiredArgsConstructor
public class NotificationAfterCommitDispatchListener {

    private final NotificationDispatchService notificationDispatchService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(NotificationDueEvent event) {
        notificationDispatchService.dispatchScheduledNotification(event.notificationId());
    }
}
