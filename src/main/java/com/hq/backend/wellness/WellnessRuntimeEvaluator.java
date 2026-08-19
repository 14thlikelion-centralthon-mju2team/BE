package com.hq.backend.wellness;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanContext;
import com.hq.backend.plan.PlanContextRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.setting.UserSettingRepository;
import com.hq.backend.wellness.dto.WellnessEngineRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the conservative M3 TR-11 runtime state immediately before a scheduler tick. */
@Service
public class WellnessRuntimeEvaluator {
    private final WellnessEngineClient engineClient;
    private final EventRepository eventRepository;
    private final PlanRevisionRepository revisionRepository;
    private final PlanContextRepository contextRepository;
    private final UserWellnessPrefRepository prefRepository;
    private final UserSettingRepository settingRepository;
    private final WellnessEventScheduleRepository scheduleRepository;
    private final PlanWellnessScoreRepository scoreRepository;

    public WellnessRuntimeEvaluator(WellnessEngineClient engineClient, EventRepository eventRepository,
            PlanRevisionRepository revisionRepository, PlanContextRepository contextRepository, UserWellnessPrefRepository prefRepository,
            UserSettingRepository settingRepository, WellnessEventScheduleRepository scheduleRepository,
            PlanWellnessScoreRepository scoreRepository) {
        this.engineClient = engineClient;
        this.eventRepository = eventRepository;
        this.revisionRepository = revisionRepository;
        this.contextRepository = contextRepository;
        this.prefRepository = prefRepository;
        this.settingRepository = settingRepository;
        this.scheduleRepository = scheduleRepository;
        this.scoreRepository = scoreRepository;
    }

    @Transactional
    public void refreshArmedAction(PlanRevision revision, Instant now) {
        Event event = eventRepository.findById(revision.getEventId()).orElse(null);
        PlanContext context = contextRepository.findById(revision.getPlanId()).orElse(null);
        PlanWellnessScore score = scoreRepository.findById(revision.getPlanId()).orElse(null);
        if (event == null || context == null || score == null) return;

        List<WellnessEventSchedule> schedules = scheduleRepository.findByPlanId(revision.getPlanId());
        List<UserWellnessPref> preferences = prefRepository.findByUserId(event.getUserId());
        UserDayWellnessState userDayState = buildUserDayState(event.getUserId(), preferences, now);
        WellnessEngineRequest.WellnessEventState state = new WellnessEngineRequest.WellnessEventState(
                settingRepository.findById(event.getUserId()).map(s -> s.isWellnessEventEnabled()).orElse(false),
                "enroute".equals(event.getStatus()), context.getEstimatedOutdoorMinutes(), false, null,
                schedules.stream().filter(s -> "completed".equals(s.getResponseAction()))
                        .map(WellnessEventSchedule::getActionCode).toList(),
                userDayState.stopTodayActionCodes(), 0, List.of(), userDayState.topicStates());
        Double feelsLike = context.getFeelsLike() == null
                ? null
                : Double.valueOf(context.getFeelsLike().doubleValue());
        if (feelsLike == null && context.getTemperature() != null) {
            feelsLike = context.getTemperature().doubleValue();
        }
        WellnessEngineRequest request = new WellnessEngineRequest(
                new WellnessEngineRequest.EnvironmentSnapshot(
                        context.getPrecipitationProb() == null ? null : context.getPrecipitationProb().intValue(),
                        feelsLike,
                        context.getUvIndex() == null ? null : context.getUvIndex().doubleValue(), context.getPm10(), null, null, null,
                        context.getObservedAt()),
                context.getEstimatedOutdoorMinutes(),
                preferences.stream().map(p -> new WellnessEngineRequest.WellnessPreference(
                        p.getWellnessTopic(), p.isEnabled(), p.getRemindIntervalMinutes(), p.getDailyEventCap())).toList(),
                List.of(), new WellnessEngineRequest.EngineConfig(0.35, .25, .20, .20, 1.25, 120, 40, 70, "w1"), state);
        engineClient.evaluate(request).ifPresent(output -> score.setArmedActionCode(
                output.eventArmed() ? output.armedActionCode() : null));
    }

    private UserDayWellnessState buildUserDayState(UUID userId, List<UserWellnessPref> preferences, Instant now) {
        LocalDate today = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        Instant startOfDay = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Instant endOfDay = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        List<UUID> eventIds = eventRepository.findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(
                        userId, startOfDay, endOfDay)
                .stream()
                .map(Event::getEventId)
                .toList();
        if (eventIds.isEmpty()) {
            return emptyUserDayState(preferences);
        }

        List<UUID> planIds = revisionRepository.findByEventIdIn(eventIds).stream()
                .map(PlanRevision::getPlanId)
                .toList();
        if (planIds.isEmpty()) {
            return emptyUserDayState(preferences);
        }
        return aggregateUserDayState(preferences, scheduleRepository.findByPlanIdIn(planIds), startOfDay, endOfDay, now);
    }

    private UserDayWellnessState emptyUserDayState(List<UserWellnessPref> preferences) {
        return aggregateUserDayState(preferences, List.of(), null, null, null);
    }

    private UserDayWellnessState aggregateUserDayState(List<UserWellnessPref> preferences,
            List<WellnessEventSchedule> schedules, Instant startOfDay, Instant endOfDay, Instant now) {
        TreeSet<String> topics = new TreeSet<>();
        preferences.stream().map(UserWellnessPref::getWellnessTopic)
                .filter(WellnessActionCatalog::isKnownTopic)
                .forEach(topics::add);
        schedules.stream().map(WellnessEventSchedule::getActionCode)
                .map(WellnessActionCatalog::topicFor)
                .filter(java.util.Objects::nonNull)
                .forEach(topics::add);

        Map<String, WellnessEngineRequest.WellnessTopicState> topicStates = new java.util.LinkedHashMap<>();
        for (String topic : topics) {
            List<Instant> sentToday = schedules.stream()
                    .filter(schedule -> topic.equals(WellnessActionCatalog.topicFor(schedule.getActionCode())))
                    .map(WellnessEventSchedule::getSentAt)
                    .filter(java.util.Objects::nonNull)
                    .filter(sentAt -> startOfDay != null && !sentAt.isBefore(startOfDay) && sentAt.isBefore(endOfDay))
                    .toList();
            Instant lastSent = sentToday.stream().max(Instant::compareTo).orElse(null);
            Integer minutesSinceLast = lastSent == null || now == null
                    ? null
                    : (int) Math.max(0, Duration.between(lastSent, now).toMinutes());
            topicStates.put(topic, new WellnessEngineRequest.WellnessTopicState(sentToday.size(), minutesSinceLast));
        }
        List<String> stopTodayActionCodes = schedules.stream()
                .filter(schedule -> "stop_today".equals(schedule.getResponseAction()))
                .map(WellnessEventSchedule::getActionCode)
                .distinct()
                .sorted()
                .toList();
        return new UserDayWellnessState(Map.copyOf(topicStates), stopTodayActionCodes);
    }

    private record UserDayWellnessState(
            Map<String, WellnessEngineRequest.WellnessTopicState> topicStates,
            List<String> stopTodayActionCodes) {
    }
}
