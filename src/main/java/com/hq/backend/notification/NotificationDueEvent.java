package com.hq.backend.notification;

import java.util.UUID;

/**
 * 계획 평가 transaction이 commit된 뒤 발송할 due notification을 나타낸다.
 * 외부 FCM 호출은 이 이벤트의 AFTER_COMMIT listener에서 수행한다.
 */
public record NotificationDueEvent(UUID notificationId) {
}
