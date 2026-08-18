package com.hq.backend.wellness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 웰니스 이벤트가 알림을 생성할 때 사용하는 포트.
 * notification 패키지와의 직접 의존을 피하기 위해 추상화.
 * 실제 구현은 notification 패키지에서 제공.
 */
public interface WellnessNotificationPort {

    /** dedup_key로 이미 존재하는 알림 확인 */
    boolean existsByDedupKey(String dedupKey);

    /** 웰니스 알림 생성. 생성된 notification_id 반환. */
    UUID createWellnessNotification(UUID planId, Instant scheduledAt,
                                    String bodyMasked, String triggerReason,
                                    String dedupKey);
}
