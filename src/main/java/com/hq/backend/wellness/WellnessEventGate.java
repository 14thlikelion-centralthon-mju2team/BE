package com.hq.backend.wellness;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanContextRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.setting.UserSettingRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * TRD §7.4 / TR-11 — 웰니스 이벤트 6중 게이트.
 * 모든 게이트가 AND로 통과해야 웰니스 이벤트를 발사한다.
 *
 * ① 사용자가 항목과 이벤트 알림을 켬 (user_wellness_pref.is_enabled)
 * ② WIS ≥ WELLNESS_EVENT_MIN (70)
 * ③ 야외 노출 지속 (일정 ENROUTE + 경로 outdoor_sec > 0 또는 야외 일정)
 * ④ 사용자 설정 재알림 주기 도달
 * ⑤ 같은 일정·같은 행동에 completed/stop_today 없음
 * ⑥ 일일 상한 미초과 (user_wellness_pref.daily_event_cap)
 */
@Service
public class WellnessEventGate {

    private static final Logger log = LoggerFactory.getLogger(WellnessEventGate.class);
    private static final int WELLNESS_EVENT_MIN_SCORE = 70;

    private final UserWellnessPrefRepository prefRepository;
    private final PlanWellnessScoreRepository scoreRepository;
    private final WellnessEventScheduleRepository scheduleRepository;
    private final EventRepository eventRepository;
    private final PlanRevisionRepository planRevisionRepository;
    private final PlanContextRepository planContextRepository;
    private final UserSettingRepository userSettingRepository;

    public WellnessEventGate(UserWellnessPrefRepository prefRepository,
                             PlanWellnessScoreRepository scoreRepository,
                             WellnessEventScheduleRepository scheduleRepository,
                             EventRepository eventRepository,
                             PlanRevisionRepository planRevisionRepository,
                             PlanContextRepository planContextRepository,
                             UserSettingRepository userSettingRepository) {
        this.prefRepository = prefRepository;
        this.scoreRepository = scoreRepository;
        this.scheduleRepository = scheduleRepository;
        this.eventRepository = eventRepository;
        this.planRevisionRepository = planRevisionRepository;
        this.planContextRepository = planContextRepository;
        this.userSettingRepository = userSettingRepository;
    }

    /**
     * 주어진 계획·행동에 대해 6중 게이트를 평가한다.
     * @return 모든 게이트 통과 시 true
     */
    public boolean evaluate(PlanRevision revision, String actionCode, Instant now) {
        UUID planId = revision.getPlanId();
        UUID eventId = revision.getEventId();

        Event event = eventRepository.findById(eventId).orElse(null);
        if (event == null) {
            return false;
        }

        UUID userId = event.getUserId();
        if (!userSettingRepository.findById(userId).map(setting -> setting.isWellnessEventEnabled()).orElse(false)) {
            log.debug("[WellnessGate] global wellness event disabled: user_id={}", userId);
            return false;
        }
        boolean hasOutdoorExposure = planContextRepository.findById(planId)
                .map(context -> context.getEstimatedOutdoorMinutes() != null && context.getEstimatedOutdoorMinutes() > 0)
                .orElse(false);
        if (!hasOutdoorExposure) {
            log.debug("[WellnessGate] no outdoor exposure: plan_id={}", planId);
            return false;
        }
        String topic = actionCodeToTopic(actionCode);
        if (topic == null) {
            log.warn("[WellnessGate] 승인되지 않은 wellness action code를 차단합니다: {}", actionCode);
            return false;
        }

        // ① 사용자가 해당 항목과 이벤트 알림을 켬
        Optional<UserWellnessPref> prefOpt = prefRepository.findByUserIdAndWellnessTopic(userId, topic);
        if (prefOpt.isEmpty() || !prefOpt.get().isEnabled()) {
            log.debug("[WellnessGate] 게이트① 실패: user_id={}, topic={} 비활성", userId, topic);
            return false;
        }
        UserWellnessPref pref = prefOpt.get();

        // ② WIS ≥ 70
        Optional<PlanWellnessScore> scoreOpt = scoreRepository.findById(planId);
        if (scoreOpt.isEmpty() || scoreOpt.get().getWisScore() < WELLNESS_EVENT_MIN_SCORE) {
            log.debug("[WellnessGate] 게이트② 실패: plan_id={}, WIS < {}", planId, WELLNESS_EVENT_MIN_SCORE);
            return false;
        }

        // ③ 야외 노출 지속: 일정이 ENROUTE 상태
        if (!"enroute".equals(event.getStatus())) {
            log.debug("[WellnessGate] 게이트③ 실패: event_id={}, status={} (ENROUTE 아님)", eventId, event.getStatus());
            return false;
        }

        // ④ 재알림 주기 도달: 마지막 발송 이후 intervalMinutes 경과 여부
        if (pref.getRemindIntervalMinutes() != null) {
            Optional<WellnessEventSchedule> lastSent = findLastSentForAction(planId, actionCode);
            if (lastSent.isPresent() && lastSent.get().getSentAt() != null) {
                Instant nextAllowed = lastSent.get().getSentAt()
                        .plusSeconds((long) pref.getRemindIntervalMinutes() * 60);
                if (now.isBefore(nextAllowed)) {
                    log.debug("[WellnessGate] 게이트④ 실패: plan_id={}, 주기 미도달", planId);
                    return false;
                }
            }
        }

        // ⑤ 같은 일정·같은 행동에 completed 없음, stop_today는 사용자·당일 전체 차단
        List<WellnessEventSchedule> existing = scheduleRepository.findByPlanIdAndActionCode(planId, actionCode);
        boolean hasCompleted = existing.stream().anyMatch(e -> "completed".equals(e.getResponseAction()));
        if (hasCompleted || hasStopTodayForUser(userId, actionCode, now)) {
            log.debug("[WellnessGate] 게이트⑤ 실패: user_id={}, action={} completed/stop_today", userId, actionCode);
            return false;
        }

        // ⑥ 일일 상한 미초과
        int dailyCap = pref.getDailyEventCap();
        long todayCount = countTodayEventsForTopic(userId, topic, now);
        if (todayCount >= dailyCap) {
            log.debug("[WellnessGate] 게이트⑥ 실패: user_id={}, topic={}, today={}/{}", userId, topic, todayCount, dailyCap);
            return false;
        }

        log.info("[WellnessGate] 6중 게이트 통과: plan_id={}, action={}", planId, actionCode);
        return true;
    }

    private Optional<WellnessEventSchedule> findLastSentForAction(UUID planId, String actionCode) {
        return scheduleRepository.findByPlanIdAndActionCode(planId, actionCode)
                .stream()
                .filter(e -> e.getSentAt() != null)
                .max(java.util.Comparator.comparing(WellnessEventSchedule::getSentAt));
    }

    private long countTodayEventsForTopic(UUID userId, String topic, Instant now) {
        LocalDate today = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        Instant startOfDay = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Instant endOfDay = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();

        // 해당 사용자의 오늘 일정들의 plan에서 해당 topic의 웰니스 이벤트 수.
        // scheduleRepository.findAll()로 전체 사용자를 다 훑으면 다른 사용자의 오늘 발송
        // 건수까지 이 사용자의 상한 소진량으로 잡힌다 — userId로 좁힌 이벤트의 planId만
        // 대상으로 삼는다(원래는 findByUserIdAndStartsAtBetween으로만 걸러서 크로스유저였음).
        List<Event> todayEvents = eventRepository.findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(
                userId, startOfDay, endOfDay);
        if (todayEvents.isEmpty()) {
            return 0;
        }

        List<UUID> eventIds = todayEvents.stream().map(Event::getEventId).toList();
        List<UUID> planIds = planRevisionRepository.findByEventIdIn(eventIds).stream()
                .map(PlanRevision::getPlanId)
                .toList();
        if (planIds.isEmpty()) {
            return 0;
        }

        return scheduleRepository.findByPlanIdIn(planIds).stream()
                .filter(s -> actionCodeToTopic(s.getActionCode()).equals(topic))
                .filter(s -> s.getSentAt() != null)
                .filter(s -> !s.getSentAt().isBefore(startOfDay) && s.getSentAt().isBefore(endOfDay))
                .count();
    }

    private boolean hasStopTodayForUser(UUID userId, String actionCode, Instant now) {
        LocalDate today = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        Instant startOfDay = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Instant endOfDay = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        List<UUID> eventIds = eventRepository.findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(
                        userId, startOfDay, endOfDay)
                .stream()
                .map(Event::getEventId)
                .toList();
        if (eventIds.isEmpty()) {
            return false;
        }
        List<UUID> planIds = planRevisionRepository.findByEventIdIn(eventIds).stream()
                .map(PlanRevision::getPlanId)
                .toList();
        return scheduleRepository.findByPlanIdIn(planIds).stream()
                .anyMatch(schedule -> actionCode.equals(schedule.getActionCode())
                        && "stop_today".equals(schedule.getResponseAction()));
    }

    /** M3 approved action_code → wellness_topic mapping. Unknown codes are rejected by evaluate(). */
    static String actionCodeToTopic(String actionCode) {
        return WellnessActionCatalog.topicFor(actionCode);
    }
}
