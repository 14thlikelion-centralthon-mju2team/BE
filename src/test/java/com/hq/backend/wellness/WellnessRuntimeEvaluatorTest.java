package com.hq.backend.wellness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanContext;
import com.hq.backend.plan.PlanContextRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.setting.UserSetting;
import com.hq.backend.setting.UserSettingRepository;
import com.hq.backend.wellness.dto.WellnessEngineRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WellnessRuntimeEvaluatorTest {

    @Mock private WellnessEngineClient engineClient;
    @Mock private EventRepository eventRepository;
    @Mock private PlanRevisionRepository revisionRepository;
    @Mock private PlanContextRepository contextRepository;
    @Mock private UserWellnessPrefRepository prefRepository;
    @Mock private UserSettingRepository settingRepository;
    @Mock private WellnessEventScheduleRepository scheduleRepository;
    @Mock private PlanWellnessScoreRepository scoreRepository;

    @Test
    void 사용자_KST_당일의_발송이력을_topic별_AI_계약으로_보낸다() throws Exception {
        Instant now = Instant.parse("2026-06-01T03:00:00Z"); // KST 12:00
        UUID userId = UUID.randomUUID();
        UUID currentEventId = UUID.randomUUID();
        UUID previousUvEventId = UUID.randomUUID();
        UUID pmEventId = UUID.randomUUID();
        UUID currentPlanId = UUID.randomUUID();
        UUID previousUvPlanId = UUID.randomUUID();
        UUID pmPlanId = UUID.randomUUID();
        PlanRevision currentRevision = PlanRevision.builder()
                .planId(currentPlanId).eventId(currentEventId).build();
        PlanRevision previousUvRevision = PlanRevision.builder()
                .planId(previousUvPlanId).eventId(previousUvEventId).build();
        PlanRevision pmRevision = PlanRevision.builder()
                .planId(pmPlanId).eventId(pmEventId).build();
        Event currentEvent = Event.builder().eventId(currentEventId).userId(userId).status("enroute").build();
        Event previousUvEvent = Event.builder().eventId(previousUvEventId).userId(userId).build();
        Event pmEvent = Event.builder().eventId(pmEventId).userId(userId).build();

        WellnessEventSchedule currentCompleted = schedule(currentPlanId, "uv_reapply", null, "completed");
        WellnessEventSchedule latestUv = schedule(previousUvPlanId, "uv_reapply", now.minusSeconds(30 * 60), "stop_today");
        WellnessEventSchedule earlierUv = schedule(previousUvPlanId, "uv_reapply", now.minusSeconds(120 * 60), null);
        WellnessEventSchedule latestPm = schedule(pmPlanId, "pm_recheck", now.minusSeconds(10 * 60), null);

        when(eventRepository.findById(currentEventId)).thenReturn(Optional.of(currentEvent));
        when(contextRepository.findById(currentPlanId)).thenReturn(Optional.of(
                PlanContext.builder().planId(currentPlanId).estimatedOutdoorMinutes(45).build()));
        when(scoreRepository.findById(currentPlanId)).thenReturn(Optional.of(
                PlanWellnessScore.builder().planId(currentPlanId).build()));
        when(prefRepository.findByUserId(userId)).thenReturn(List.of(
                preference(userId, "uv"), preference(userId, "pm"), preference(userId, "hydration")));
        UserSetting setting = mock(UserSetting.class);
        when(setting.isWellnessEventEnabled()).thenReturn(true);
        when(settingRepository.findById(userId)).thenReturn(Optional.of(setting));
        when(scheduleRepository.findByPlanId(currentPlanId)).thenReturn(List.of(currentCompleted));
        when(eventRepository.findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(any(), any(), any()))
                .thenReturn(List.of(currentEvent, previousUvEvent, pmEvent));
        when(revisionRepository.findByEventIdIn(List.of(currentEventId, previousUvEventId, pmEventId)))
                .thenReturn(List.of(currentRevision, previousUvRevision, pmRevision));
        when(scheduleRepository.findByPlanIdIn(List.of(currentPlanId, previousUvPlanId, pmPlanId)))
                .thenReturn(List.of(currentCompleted, latestUv, earlierUv, latestPm));
        when(engineClient.evaluate(any())).thenReturn(Optional.empty());

        evaluator().refreshArmedAction(currentRevision, now);

        ArgumentCaptor<WellnessEngineRequest> requestCaptor = ArgumentCaptor.forClass(WellnessEngineRequest.class);
        verify(engineClient).evaluate(requestCaptor.capture());
        WellnessEngineRequest.WellnessEventState state = requestCaptor.getValue().eventState();

        assertThat(state.topicStates().get("uv").dailyEventCount()).isEqualTo(2);
        assertThat(state.topicStates().get("uv").minutesSinceLastEvent()).isEqualTo(30);
        assertThat(state.topicStates().get("pm").dailyEventCount()).isEqualTo(1);
        assertThat(state.topicStates().get("pm").minutesSinceLastEvent()).isEqualTo(10);
        assertThat(state.topicStates().get("hydration").dailyEventCount()).isZero();
        assertThat(state.topicStates().get("hydration").minutesSinceLastEvent()).isNull();
        assertThat(state.stopTodayActionCodes()).containsExactly("uv_reapply");
        assertThat(state.completedActionCodes()).containsExactly("uv_reapply");
        assertThat(state.dailyEventCount()).isZero();
        assertThat(state.minutesSinceLastEvent()).isNull();

        String json = new ObjectMapper().writeValueAsString(requestCaptor.getValue());
        assertThat(json).contains("\"topicStates\"");
        assertThat(json).contains("\"dailyEventCount\":2");
        assertThat(json).doesNotContain("topic_states");
    }

    private WellnessRuntimeEvaluator evaluator() {
        return new WellnessRuntimeEvaluator(engineClient, eventRepository, revisionRepository, contextRepository,
                prefRepository, settingRepository, scheduleRepository, scoreRepository);
    }

    private UserWellnessPref preference(UUID userId, String topic) {
        return UserWellnessPref.builder()
                .userId(userId).wellnessTopic(topic).isEnabled(true)
                .remindIntervalMinutes(60).dailyEventCap(2).updatedAt(Instant.EPOCH)
                .build();
    }

    private WellnessEventSchedule schedule(UUID planId, String actionCode, Instant sentAt, String responseAction) {
        return WellnessEventSchedule.builder()
                .planId(planId).actionCode(actionCode).scheduledAt(Instant.EPOCH)
                .sentAt(sentAt).responseAction(responseAction).sequenceNo((short) 1)
                .build();
    }
}
