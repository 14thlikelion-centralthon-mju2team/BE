package com.hq.backend.notification;

import com.hq.backend.plan.PlanRevision;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * TRD §8.2 — 30초 틱 오케스트레이터.
 * plan_revision.next_eval_at 기반으로 재평가 대상을 폴링하고,
 * 알림 예약·실질 변화 판정·next_eval_at 갱신을 수행한다.
 *
 * 복구 전략(§13.3): 진행 상태를 메모리에 두지 않는다.
 * 어느 시점에 죽어도 재시작 시 next_eval_at 기준으로 이어 달린다.
 */
@Component
public class PlanOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PlanOrchestrator.class);
    private static final int BATCH_SIZE = 200;

    private final PlanEvalRepository planEvalRepository;
    private final NotificationScheduler notificationScheduler;
    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final com.hq.backend.wellness.WellnessEventSchedulerService wellnessEventSchedulerService;

    public PlanOrchestrator(PlanEvalRepository planEvalRepository,
                            NotificationScheduler notificationScheduler,
                            NotificationRepository notificationRepository,
                            ApplicationEventPublisher eventPublisher,
                            com.hq.backend.wellness.WellnessEventSchedulerService wellnessEventSchedulerService) {
        this.planEvalRepository = planEvalRepository;
        this.notificationScheduler = notificationScheduler;
        this.notificationRepository = notificationRepository;
        this.eventPublisher = eventPublisher;
        this.wellnessEventSchedulerService = wellnessEventSchedulerService;
    }

    /**
     * 30초마다 실행. next_eval_at이 도래한 활성 계획을 평가한다.
     * FOR UPDATE SKIP LOCKED로 워커 경합 없이 처리.
     */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void tick() {
        Instant now = Instant.now();
        List<PlanRevision> dueRevisions = planEvalRepository.findDueForEvaluation(now, BATCH_SIZE);

        if (dueRevisions.isEmpty()) {
            return;
        }

        log.debug("[Orchestrator] {} 건 평가 시작 (now={})", dueRevisions.size(), now);

        for (PlanRevision revision : dueRevisions) {
            try {
                evaluate(revision, now);
            } catch (Exception e) {
                log.error("[Orchestrator] plan_id={} 평가 실패", revision.getPlanId(), e);
                // 실패해도 다음 틱에서 재시도 — next_eval_at이 갱신되지 않았으므로 다시 잡힘
            }
        }
    }

    /**
     * 개별 계획 평가. 알림 예약 + 도래한 알림 dispatch event 발행.
     * FCM I/O는 transaction commit 후 listener가 처리한다.
     */
    private void evaluate(PlanRevision revision, Instant now) {
        notificationScheduler.scheduleTimeSlots(revision, now);
        wellnessEventSchedulerService.tryFireWellnessEvents(revision, now);
        enqueueDueNotifications(revision, now);

        Instant nextEval = computeNextEvalAt(revision, now);
        revision.setNextEvalAt(nextEval);

        log.debug("[Orchestrator] plan_id={} 평가 완료, next_eval_at={}",
                revision.getPlanId(), nextEval);
    }

    /** due notification은 DB transaction이 commit된 뒤 FCM dispatch한다. */
    private void enqueueDueNotifications(PlanRevision revision, Instant now) {
        notificationRepository.findByPlanIdAndDeliveryStatus(revision.getPlanId(), "scheduled")
                .stream()
                .filter(n -> !n.getScheduledAt().isAfter(now))
                .forEach(n -> eventPublisher.publishEvent(new NotificationDueEvent(n.getNotificationId())));
    }

    /**
     * TRD §8.2 구간별 재평가 주기:
     * - 준비 시작 6시간 전~: 60분
     * - 6시간 ~ 90분 전: 20분
     * - 90분 전 ~ 준비 시작: 5분
     * - 준비 시작 ~ 출발: 3분
     * - 이동 중(ENROUTE): 5분
     *
     * 일정이 이미 지났으면 null(큐에서 빠짐).
     */
    Instant computeNextEvalAt(PlanRevision revision, Instant now) {
        Instant prepStart = revision.getPrepStartAt();
        Instant depart = revision.getRecommendedDepartAt();
        Instant arrive = revision.getTargetArriveAt();

        if (now.isAfter(arrive.plusSeconds(1800))) {
            return null;
        }

        long minutesToPrepStart = java.time.Duration.between(now, prepStart).toMinutes();

        if (now.isAfter(depart)) {
            return now.plusSeconds(5 * 60);
        } else if (now.isAfter(prepStart) || minutesToPrepStart <= 0) {
            return now.plusSeconds(3 * 60);
        } else if (minutesToPrepStart <= 90) {
            return now.plusSeconds(5 * 60);
        } else if (minutesToPrepStart <= 360) {
            return now.plusSeconds(20 * 60);
        } else {
            return now.plusSeconds(60 * 60);
        }
    }
}
