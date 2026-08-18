package com.hq.backend.notification;

import com.hq.backend.plan.PlanRevision;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final NotificationService notificationService;

    public PlanOrchestrator(PlanEvalRepository planEvalRepository,
                            NotificationScheduler notificationScheduler,
                            NotificationRepository notificationRepository,
                            NotificationService notificationService) {
        this.planEvalRepository = planEvalRepository;
        this.notificationScheduler = notificationScheduler;
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
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
     * 개별 계획 평가. 알림 예약 + 도래한 알림 아웃박스 투입.
     * 향후 환경 변화 감지 → 재계산 → 실질 변화 판정(TRD §8.3) 추가 예정.
     */
    private void evaluate(PlanRevision revision, Instant now) {
        // 1. 시간 알림 슬롯 예약 (여유A / 극한B / 돌발C)
        notificationScheduler.scheduleTimeSlots(revision, now);

        // 2. 발송 시각이 도래한 알림을 아웃박스에 투입
        enqueueDueNotifications(revision, now);

        // 3. next_eval_at 갱신 — 구간별 주기(TRD §8.2)
        Instant nextEval = computeNextEvalAt(revision, now);
        revision.setNextEvalAt(nextEval);

        log.debug("[Orchestrator] plan_id={} 평가 완료, next_eval_at={}",
                revision.getPlanId(), nextEval);
    }

    /** 해당 plan의 scheduled 알림 중 시각이 도래한 것을 FCM으로 발송 */
    private void enqueueDueNotifications(PlanRevision revision, Instant now) {
        notificationRepository.findByPlanIdAndDeliveryStatus(revision.getPlanId(), "scheduled")
                .stream()
                .filter(n -> !n.getScheduledAt().isAfter(now))
                .forEach(n -> {
                    notificationService.sendNotification(n, revision);
                });
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

        // 이미 도착 시각을 지남 → 평가 종료
        if (now.isAfter(arrive.plusSeconds(1800))) { // 도착 후 30분 유예
            return null;
        }

        long minutesToPrepStart = java.time.Duration.between(now, prepStart).toMinutes();

        if (now.isAfter(depart)) {
            // 이동 중 구간
            return now.plusSeconds(5 * 60);
        } else if (now.isAfter(prepStart) || minutesToPrepStart <= 0) {
            // 준비 시작 ~ 출발
            return now.plusSeconds(3 * 60);
        } else if (minutesToPrepStart <= 90) {
            // 90분 전 ~ 준비 시작
            return now.plusSeconds(5 * 60);
        } else if (minutesToPrepStart <= 360) {
            // 6시간 ~ 90분 전
            return now.plusSeconds(20 * 60);
        } else {
            // 6시간 이상 전
            return now.plusSeconds(60 * 60);
        }
    }
}
