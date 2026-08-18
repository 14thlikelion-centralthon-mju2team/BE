package com.hq.backend.notification;

import com.hq.backend.plan.PlanRevision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * TRD §8.1 — 시간 알림 3슬롯 예약.
 *
 * A(여유): 준비 시작 10분 전 — "곧 준비를 시작해야 해요"
 * B(극한): 준비 시작 시각 — "지금 시작하면 딱 맞아요"
 * C(돌발): 실질 변화 Δ≥5분 시 즉시 — "일정이 변경되었어요"
 *
 * 예산: 일정당 시간 알림 최대 3회 (PRD §13.5).
 * dedup_key = sha1(event_id:slot:revision_no)로 중복 발송 구조적 차단.
 */
@Service
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);
    private static final int TIME_NOTIFICATION_BUDGET = 3;

    private final NotificationRepository notificationRepository;

    public NotificationScheduler(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * 활성 계획에 대해 A/B 슬롯 알림을 예약한다.
     * 이미 dedup_key가 존재하면 스킵(멱등).
     * 예산 초과 시 예약하지 않는다.
     */
    public void scheduleTimeSlots(PlanRevision revision, Instant now) {
        UUID planId = revision.getPlanId();
        UUID eventId = revision.getEventId();
        int revisionNo = revision.getRevisionNo();
        Instant prepStart = revision.getPrepStartAt();

        // 예산 확인
        int currentCount = notificationRepository.countTimeNotificationsByPlanId(planId);
        if (currentCount >= TIME_NOTIFICATION_BUDGET) {
            log.debug("[Scheduler] plan_id={} 예산 소진({}회), 추가 예약 스킵", planId, currentCount);
            return;
        }

        int remaining = TIME_NOTIFICATION_BUDGET - currentCount;

        // A슬롯: 준비 시작 10분 전 (아직 도래하지 않았으면 예약)
        if (remaining > 0) {
            Instant slotA = prepStart.minus(Duration.ofMinutes(10));
            if (slotA.isAfter(now)) {
                boolean scheduled = trySchedule(planId, eventId, revisionNo, "A",
                        slotA, "relaxed",
                        "곧 준비를 시작할 시간이에요",
                        "준비 시작 10분 전 여유 알림");
                if (scheduled) remaining--;
            }
        }

        // B슬롯: 준비 시작 시각 (아직 도래하지 않았으면 예약)
        if (remaining > 0) {
            if (prepStart.isAfter(now)) {
                trySchedule(planId, eventId, revisionNo, "B",
                        prepStart, "critical",
                        "지금 준비를 시작하면 딱 맞아요",
                        "준비 시작 시각 극한 알림");
            }
        }
    }

    /**
     * C슬롯(돌발) 알림 예약 — 실질 변화 Δ≥5분 시 호출.
     * 기존 C슬롯이 있으면 교체(최신 1건 유지, TRD §8.1).
     */
    public void scheduleDisruption(PlanRevision revision, String reason) {
        UUID planId = revision.getPlanId();
        int currentCount = notificationRepository.countTimeNotificationsByPlanId(planId);
        if (currentCount >= TIME_NOTIFICATION_BUDGET) {
            log.debug("[Scheduler] plan_id={} 예산 소진, 돌발 알림 스킵", planId);
            return;
        }

        // 기존 C슬롯 취소
        cancelSlot(planId, revision.getEventId(), "C");

        trySchedule(planId, revision.getEventId(), revision.getRevisionNo(), "C",
                Instant.now(), "disruption",
                "일정이 변경되었어요",
                reason);
    }

    /**
     * 알림 예약 시도. dedup_key 충돌 시 이미 예약됨으로 간주해 스킵.
     * @return true면 새로 예약됨
     */
    private boolean trySchedule(UUID planId, UUID eventId, int revisionNo,
                                String slot, Instant scheduledAt, String type,
                                String bodyMasked, String triggerReason) {
        String dedupKey = computeDedupKey(eventId, slot, revisionNo);

        // 이미 존재 → 멱등
        Optional<Notification> existing = notificationRepository.findByDedupKey(dedupKey);
        if (existing.isPresent()) {
            log.debug("[Scheduler] dedup_key={} 이미 존재, 스킵", dedupKey);
            return false;
        }

        Notification notification = Notification.builder()
                .planId(planId)
                .notificationCategory("time")
                .notificationType(type)
                .scheduledAt(scheduledAt)
                .deliveryStatus("scheduled")
                .bodyMasked(bodyMasked)
                .triggerReason(triggerReason)
                .dedupKey(dedupKey)
                .build();

        notificationRepository.save(notification);
        log.info("[Scheduler] 알림 예약: plan_id={}, slot={}, type={}, at={}",
                planId, slot, type, scheduledAt);
        return true;
    }

    /** 특정 슬롯의 기존 scheduled 알림 취소 (C슬롯 교체용) */
    private void cancelSlot(UUID planId, UUID eventId, String slot) {
        // C슬롯은 이전 리비전의 것을 취소해야 하므로 prefix 매칭
        // 간단히 plan_id + scheduled 상태인 disruption 타입을 취소
        notificationRepository.findByPlanIdAndDeliveryStatus(planId, "scheduled")
                .stream()
                .filter(n -> "disruption".equals(n.getNotificationType()))
                .forEach(n -> n.setDeliveryStatus("cancelled"));
    }

    /**
     * TRD §8.4 — dedup_key = sha1(event_id:slot:revision_no)
     */
    static String computeDedupKey(UUID eventId, String slot, int revisionNo) {
        String input = eventId + ":" + slot + ":" + revisionNo;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1은 모든 JVM에 필수. 실질적으로 도달 불가.
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
