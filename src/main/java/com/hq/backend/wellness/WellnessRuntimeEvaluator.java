package com.hq.backend.wellness;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanContext;
import com.hq.backend.plan.PlanContextRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.setting.UserSettingRepository;
import com.hq.backend.wellness.dto.WellnessEngineRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the conservative M3 TR-11 runtime state immediately before a scheduler tick. */
@Service
public class WellnessRuntimeEvaluator {
    private final WellnessEngineClient engineClient;
    private final EventRepository eventRepository;
    private final PlanContextRepository contextRepository;
    private final UserWellnessPrefRepository prefRepository;
    private final UserSettingRepository settingRepository;
    private final WellnessEventScheduleRepository scheduleRepository;
    private final PlanWellnessScoreRepository scoreRepository;
    private final com.hq.backend.config.WellnessConfigService wellnessConfigService;

    public WellnessRuntimeEvaluator(WellnessEngineClient engineClient, EventRepository eventRepository,
            PlanContextRepository contextRepository, UserWellnessPrefRepository prefRepository,
            UserSettingRepository settingRepository, WellnessEventScheduleRepository scheduleRepository,
            PlanWellnessScoreRepository scoreRepository, com.hq.backend.config.WellnessConfigService wellnessConfigService) {
        this.engineClient = engineClient;
        this.eventRepository = eventRepository;
        this.contextRepository = contextRepository;
        this.prefRepository = prefRepository;
        this.settingRepository = settingRepository;
        this.scheduleRepository = scheduleRepository;
        this.scoreRepository = scoreRepository;
        this.wellnessConfigService = wellnessConfigService;
    }

    @Transactional
    public void refreshArmedAction(PlanRevision revision, Instant now) {
        Event event = eventRepository.findById(revision.getEventId()).orElse(null);
        PlanContext context = contextRepository.findById(revision.getPlanId()).orElse(null);
        PlanWellnessScore score = scoreRepository.findById(revision.getPlanId()).orElse(null);
        if (event == null || context == null || score == null) return;

        List<WellnessEventSchedule> schedules = scheduleRepository.findByPlanId(revision.getPlanId());
        Instant lastSent = schedules.stream().map(WellnessEventSchedule::getSentAt)
                .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
        Integer sinceLast = lastSent == null ? null : (int) Math.max(0, Duration.between(lastSent, now).toMinutes());
        WellnessEngineRequest.WellnessEventState state = new WellnessEngineRequest.WellnessEventState(
                settingRepository.findById(event.getUserId()).map(s -> s.isWellnessEventEnabled()).orElse(false),
                "enroute".equals(event.getStatus()), context.getEstimatedOutdoorMinutes(), false, sinceLast,
                schedules.stream().filter(s -> "completed".equals(s.getResponseAction())).map(WellnessEventSchedule::getActionCode).toList(),
                schedules.stream().filter(s -> "stop_today".equals(s.getResponseAction())).map(WellnessEventSchedule::getActionCode).toList(),
                0, List.of());
        Double feelsLike = context.getFeelsLike() != null ? context.getFeelsLike().doubleValue()
                : context.getTemperature() == null ? null : context.getTemperature().doubleValue();
        WellnessEngineRequest request = new WellnessEngineRequest(
                new WellnessEngineRequest.EnvironmentSnapshot(
                        context.getPrecipitationProb() == null ? null : context.getPrecipitationProb().intValue(),
                        feelsLike,
                        context.getUvIndex() == null ? null : context.getUvIndex().doubleValue(), context.getPm10(), null, null, null,
                        context.getObservedAt()),
                context.getEstimatedOutdoorMinutes(),
                prefRepository.findByUserId(event.getUserId()).stream().map(p -> new WellnessEngineRequest.WellnessPreference(
                        p.getWellnessTopic(), p.isEnabled(), p.getRemindIntervalMinutes(), p.getDailyEventCap())).toList(),
                List.of(), wellnessConfigService.current(), state);
        engineClient.evaluate(request).ifPresent(output -> score.setArmedActionCode(
                output.eventArmed() ? output.armedActionCode() : null));
    }
}
