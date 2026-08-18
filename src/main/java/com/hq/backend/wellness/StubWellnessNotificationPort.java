package com.hq.backend.wellness;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * notification 패키지가 아직 dev에 병합되기 전까지 사용하는 스텁.
 * feat/be-orchestrator-notification 병합 후 실제 어댑터로 교체.
 */
@Component
public class StubWellnessNotificationPort implements WellnessNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(StubWellnessNotificationPort.class);

    @Override
    public boolean existsByDedupKey(String dedupKey) {
        return false; // 스텁: 항상 새 알림으로 간주
    }

    @Override
    public UUID createWellnessNotification(UUID planId, Instant scheduledAt,
                                           String bodyMasked, String triggerReason,
                                           String dedupKey) {
        UUID id = UUID.randomUUID();
        log.info("[WellnessNotification-STUB] 알림 생성 시뮬레이션: plan_id={}, body='{}', dedup={}",
                planId, bodyMasked, dedupKey);
        return id;
    }
}
