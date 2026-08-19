package com.hq.backend.wellness;

import com.hq.backend.plan.PlanRevision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TRD §7.4 — 웰니스 이벤트 스케줄러.
 * 6중 게이트 통과 후 웰니스 이벤트를 예약하고, 사용자 응답을 처리한다.
 *
 * 발사: ENROUTE 상태에서 오케스트레이터가 호출
 * 응답: completed / snoozed / stop_today / ignored
 * 백오프: 연속 2회 ignored → 빈도 1단계 하향
 * dedup_key: sha1(event_id:W:action_code:revision_no)
 */
@Service
public class WellnessEventSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(WellnessEventSchedulerService.class);

    private final WellnessEventGate gate;
    private final WellnessEventScheduleRepository scheduleRepository;
    private final WellnessNotificationPort notificationPort;
    private final PlanWellnessActionRepository actionRepository;
    private final UserWellnessPrefRepository prefRepository;
    private final com.hq.backend.event.EventRepository eventRepository;
    private final com.hq.backend.plan.PlanRevisionRepository planRevisionRepository;

    public WellnessEventSchedulerService(WellnessEventGate gate,
                                         WellnessEventScheduleRepository scheduleRepository,
                                         WellnessNotificationPort notificationPort,
                                         PlanWellnessActionRepository actionRepository,
                                         UserWellnessPrefRepository prefRepository,
                                         com.hq.backend.event.EventRepository eventRepository,
                                         com.hq.backend.plan.PlanRevisionRepository planRevisionRepository) {
        this.gate = gate;
        this.scheduleRepository = scheduleRepository;
        this.notificationPort = notificationPort;
        this.actionRepository = actionRepository;
        this.prefRepository = prefRepository;
        this.eventRepository = eventRepository;
        this.planRevisionRepository = planRevisionRepository;
    }

    /**
     * ENROUTE 계획에 대해 웰니스 이벤트 발사를 시도한다.
     * plan_wellness_action에서 제안된 행동 중 게이트를 통과하는 것만 발사.
     * 일정당 웰니스 푸시 기본 1회(PRD §13.5).
     */
    @Transactional
    public void tryFireWellnessEvents(PlanRevision revision, Instant now) {
        UUID planId = revision.getPlanId();

        // 이미 이 계획에 발송된 웰니스 이벤트가 있으면 스킵 (일정당 1회)
        List<WellnessEventSchedule> existing = scheduleRepository.findByPlanId(planId);
        boolean alreadySent = existing.stream()
                .anyMatch(e -> e.getSentAt() != null && e.getCancelledAt() == null);
        if (alreadySent) {
            return;
        }

        // plan_wellness_action에서 제안된 행동 조회
        List<PlanWellnessAction> proposedActions = actionRepository.findByPlanId(planId);
        if (proposedActions.isEmpty()) {
            return;
        }

        for (PlanWellnessAction action : proposedActions) {
            if (gate.evaluate(revision, action.getActionCode(), now)) {
                fireEvent(revision, action, now);
                return; // 일정당 1회
            }
        }
    }

    /**
     * 웰니스 이벤트 발사 — notification + wellness_event_schedule 생성.
     */
    private void fireEvent(PlanRevision revision, PlanWellnessAction action, Instant now) {
        UUID planId = revision.getPlanId();
        String actionCode = action.getActionCode();
        String dedupKey = computeWellnessDedupKey(revision.getEventId(), actionCode, revision.getRevisionNo());

        // dedup 확인
        if (notificationPort.existsByDedupKey(dedupKey)) {
            log.debug("[WellnessScheduler] dedup 충돌, 스킵: {}", dedupKey);
            return;
        }

        // notification 생성 (포트 통해)
        String body = buildWellnessBody(actionCode);
        UUID notificationId = notificationPort.createWellnessNotification(
                planId, now, body, "WIS≥70, 야외 노출 지속", dedupKey);

        // wellness_event_schedule 생성
        WellnessEventSchedule schedule = WellnessEventSchedule.builder()
                .planId(planId)
                .notificationId(notificationId)
                .actionCode(actionCode)
                .scheduledAt(now)
                .sequenceNo((short) 1)
                .build();
        scheduleRepository.save(schedule);

        log.info("[WellnessScheduler] 웰니스 이벤트 발사: plan_id={}, action={}", planId, actionCode);
    }

    /**
     * 사용자 응답 처리.
     * - completed: 완료 기록
     * - snoozed: 30분 뒤 재발사 가능
     * - stop_today: 당일 해당 행동 전체 중단
     * - ignored: 연속 2회 시 빈도 하향
     */
    @Transactional
    public void handleNotificationResponse(UUID notificationId, String responseAction, UUID userId) {
        WellnessEventSchedule schedule = scheduleRepository.findByNotificationId(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("wellness notification not found: " + notificationId));
        handleResponse(schedule.getWellnessEventId(), responseAction, userId);
    }

    @Transactional
    public void handleResponse(UUID wellnessEventId, String responseAction, UUID userId) {
        WellnessEventSchedule schedule = scheduleRepository.findById(wellnessEventId)
                .orElseThrow(() -> new IllegalArgumentException("wellness event not found: " + wellnessEventId));
        assertOwner(schedule, userId);
        if (!List.of("completed", "snoozed", "stop_today", "ignored").contains(responseAction)) {
            throw new IllegalArgumentException("unsupported wellness response: " + responseAction);
        }

        schedule.setResponseAction(responseAction);
        switch (responseAction) {
            case "stop_today" -> handleStopToday(schedule, userId);
            case "ignored" -> handleIgnored(schedule, userId);
            default -> { /* completed, snoozed — response is retained for the scheduler */ }
        }
    }

    /** stop_today: KST 당일 같은 사용자의 같은 행동에 대한 미래 발송 전체 중단 */
    private void handleStopToday(WellnessEventSchedule schedule, UUID userId) {
        Instant now = Instant.now();
        log.info("[WellnessScheduler] stop_today: user_id={}, action={}", userId, schedule.getActionCode());
        List<UUID> todayPlanIds = todayPlanIds(userId, now);
        if (todayPlanIds.isEmpty()) {
            return;
        }
        scheduleRepository.findByPlanIdIn(todayPlanIds).stream()
                .filter(candidate -> schedule.getActionCode().equals(candidate.getActionCode()))
                .filter(candidate -> candidate.getSentAt() == null && candidate.getCancelledAt() == null)
                .forEach(candidate -> {
                    candidate.setCancelledAt(now);
                    candidate.setCancelReason("user_stop_today");
                    if (candidate.getNotificationId() != null) {
                        notificationPort.cancelWellnessNotification(candidate.getNotificationId());
                    }
                });
    }

    private void assertOwner(WellnessEventSchedule schedule, UUID userId) {
        com.hq.backend.plan.PlanRevision revision = planRevisionRepository.findById(schedule.getPlanId())
                .orElseThrow(() -> new IllegalArgumentException("plan not found: " + schedule.getPlanId()));
        boolean owned = eventRepository.findById(revision.getEventId())
                .map(event -> userId.equals(event.getUserId()))
                .orElse(false);
        if (!owned) {
            throw new IllegalArgumentException("wellness event not owned by user");
        }
    }

    private List<UUID> todayPlanIds(UUID userId, Instant now) {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Seoul");
        java.time.LocalDate today = now.atZone(zone).toLocalDate();
        Instant startOfDay = today.atStartOfDay(zone).toInstant();
        Instant endOfDay = today.plusDays(1).atStartOfDay(zone).toInstant();
        List<UUID> eventIds = eventRepository.findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(
                        userId, startOfDay, endOfDay)
                .stream()
                .map(com.hq.backend.event.Event::getEventId)
                .toList();
        if (eventIds.isEmpty()) {
            return List.of();
        }
        return planRevisionRepository.findByEventIdIn(eventIds).stream()
                .map(com.hq.backend.plan.PlanRevision::getPlanId)
                .toList();
    }

    /** ignored 연속 2회 → 빈도 1단계 하향 (TRD §7.4) */
    private void handleIgnored(WellnessEventSchedule schedule, UUID userId) {
        String topic = WellnessEventGate.actionCodeToTopic(schedule.getActionCode());

        // 최근 2건의 응답이 연속 ignored인지 확인
        List<WellnessEventSchedule> recent = scheduleRepository.findByPlanIdAndActionCode(
                        schedule.getPlanId(), schedule.getActionCode())
                .stream()
                .filter(s -> s.getResponseAction() != null)
                .sorted((a, b) -> b.getScheduledAt().compareTo(a.getScheduledAt()))
                .limit(2)
                .toList();

        if (recent.size() >= 2 && recent.stream().allMatch(s -> "ignored".equals(s.getResponseAction()))) {
            // 빈도 1단계 하향: remindIntervalMinutes를 1.5배로 늘림
            prefRepository.findByUserIdAndWellnessTopic(userId, topic)
                    .ifPresent(pref -> {
                        if (pref.getRemindIntervalMinutes() != null) {
                            int newInterval = (int) (pref.getRemindIntervalMinutes() * 1.5);
                            pref.setRemindIntervalMinutes(newInterval);
                            pref.setUpdatedAt(Instant.now());
                            log.info("[WellnessScheduler] 빈도 하향: user_id={}, topic={}, interval={}→{}",
                                    userId, topic, pref.getRemindIntervalMinutes(), newInterval);
                        }
                    });
        }
    }

    /** 승인 템플릿 기반 웰니스 알림 문구 (TR-09: 자유 생성 LLM 금지) */
    private String buildWellnessBody(String actionCode) {
        return switch (actionCode) {
            case "sunscreen" -> "설정하신 시간이 지났어요. 자외선 차단제를 다시 바를 타이밍이에요.";
            case "mask" -> "미세먼지가 높아요. 마스크를 착용해 주세요.";
            case "hydration" -> "물 한 잔 마실 시간이에요.";
            case "outerwear" -> "기온이 낮아요. 겉옷을 챙기세요.";
            case "umbrella" -> "비 소식이 있어요. 우산을 확인해 주세요.";
            default -> "웰니스 행동을 확인해 주세요.";
        };
    }

    /** TRD §8.4 — dedup_key = sha1(event_id:W:action_code:revision_no) */
    static String computeWellnessDedupKey(UUID eventId, String actionCode, int revisionNo) {
        String input = eventId + ":W:" + actionCode + ":" + revisionNo;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
