package com.hq.backend.wellness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanContext;
import com.hq.backend.plan.PlanContextRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.setting.UserSetting;
import com.hq.backend.setting.UserSettingRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// 게이트⑥(일일 상한) 크로스유저 버그 회귀 테스트 — 다른 사용자가 오늘 받은 같은 토픽
// 웰니스 이벤트가 이 사용자의 dailyEventCap 소진량으로 잡히면 안 된다.
@SpringBootTest
@AutoConfigureMockMvc
class WellnessEventGateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WellnessEventGate wellnessEventGate;

    @Autowired
    private WellnessEventSchedulerService wellnessEventSchedulerService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PlanRevisionRepository planRevisionRepository;

    @Autowired
    private PlanContextRepository planContextRepository;

    @Autowired
    private UserSettingRepository userSettingRepository;

    @Autowired
    private PlanWellnessScoreRepository planWellnessScoreRepository;

    @Autowired
    private UserWellnessPrefRepository userWellnessPrefRepository;

    @Autowired
    private WellnessEventScheduleRepository wellnessEventScheduleRepository;

    @Test
    void 다른_사용자가_오늘_받은_같은_토픽_이벤트는_이_사용자의_일일상한에_섞이지_않는다() throws Exception {
        UUID otherUserId = extractUserId(signupAndLogin());
        UUID userId = extractUserId(signupAndLogin());
        Instant now = Instant.now();

        // 다른 사용자: 오늘 이미 sunscreen(uv) 이벤트를 하나 받은 상태
        setUpEnrouteEventWithScore(otherUserId, now, 80);
        userWellnessPrefRepository.save(UserWellnessPref.builder()
                .userId(otherUserId).wellnessTopic("uv").isEnabled(true).dailyEventCap(1)
                .updatedAt(now).build());
        PlanRevision otherPlan = planRevisionRepository.findByEventIdOrderByRevisionNoDesc(
                eventRepository.findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(
                        otherUserId, now.minusSeconds(60), now.plusSeconds(60)).get(0).getEventId()).get(0);
        wellnessEventScheduleRepository.save(WellnessEventSchedule.builder()
                .planId(otherPlan.getPlanId()).actionCode("sunscreen")
                .scheduledAt(now).sentAt(now).sequenceNo((short) 1).build());

        // 이 사용자: dailyEventCap=1, 아직 오늘 받은 게 없음 — 게이트 전부 통과해야 정상
        setUpEnrouteEventWithScore(userId, now, 80);
        userWellnessPrefRepository.save(UserWellnessPref.builder()
                .userId(userId).wellnessTopic("uv").isEnabled(true).dailyEventCap(1)
                .updatedAt(now).build());
        Event myEvent = eventRepository.findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(
                userId, now.minusSeconds(60), now.plusSeconds(60)).get(0);
        PlanRevision myPlan = planRevisionRepository.findByEventIdOrderByRevisionNoDesc(myEvent.getEventId()).get(0);

        boolean passed = wellnessEventGate.evaluate(myPlan, "sunscreen", now);

        assertThat(passed).isTrue();
    }

    @Test
    void stop_today는_같은_사용자의_다른_당일_plan에서도_동일_action을_차단한다() throws Exception {
        UUID userId = extractUserId(signupAndLogin());
        Instant now = Instant.now();
        userWellnessPrefRepository.save(UserWellnessPref.builder()
                .userId(userId).wellnessTopic("uv").isEnabled(true).dailyEventCap(3)
                .updatedAt(now).build());

        setUpEnrouteEventWithScore(userId, now, 80);
        setUpEnrouteEventWithScore(userId, now.plusSeconds(1800), 80);
        List<Event> events = eventRepository.findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(
                userId, now.minusSeconds(60), now.plusSeconds(3600));
        PlanRevision firstPlan = planRevisionRepository.findByEventIdOrderByRevisionNoDesc(events.get(0).getEventId()).get(0);
        PlanRevision secondPlan = planRevisionRepository.findByEventIdOrderByRevisionNoDesc(events.get(1).getEventId()).get(0);
        wellnessEventScheduleRepository.save(WellnessEventSchedule.builder()
                .planId(firstPlan.getPlanId()).actionCode("sunscreen").scheduledAt(now)
                .responseAction("stop_today").sequenceNo((short) 1).build());

        assertThat(wellnessEventGate.evaluate(secondPlan, "sunscreen", now)).isFalse();
    }

    @Test
    void stop_today_응답은_DB제약을_통과하고_동일_action의_미발송_schedule을_취소한다() throws Exception {
        UUID userId = extractUserId(signupAndLogin());
        // stop_today는 KST 당일 범위만 취소한다. CI가 자정 직전에 실행돼 30분 뒤 fixture가
        // 다음 날로 넘어가지 않도록, 실제 실행일의 KST 정오를 기준으로 만든다.
        Instant now = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                .atTime(12, 0)
                .atZone(java.time.ZoneId.of("Asia/Seoul"))
                .toInstant();
        setUpEnrouteEventWithScore(userId, now, 80);
        setUpEnrouteEventWithScore(userId, now.plusSeconds(1800), 80);

        List<Event> events = eventRepository.findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(
                userId, now.minusSeconds(60), now.plusSeconds(3600));
        PlanRevision firstPlan = planRevisionRepository.findByEventIdOrderByRevisionNoDesc(events.get(0).getEventId()).get(0);
        PlanRevision secondPlan = planRevisionRepository.findByEventIdOrderByRevisionNoDesc(events.get(1).getEventId()).get(0);

        WellnessEventSchedule sent = wellnessEventScheduleRepository.saveAndFlush(WellnessEventSchedule.builder()
                .planId(firstPlan.getPlanId()).actionCode("uv_reapply").scheduledAt(now).sentAt(now)
                .sequenceNo((short) 1).build());
        WellnessEventSchedule future = wellnessEventScheduleRepository.saveAndFlush(WellnessEventSchedule.builder()
                .planId(secondPlan.getPlanId()).actionCode("uv_reapply").scheduledAt(now.plusSeconds(1800))
                .sequenceNo((short) 1).build());

        wellnessEventSchedulerService.handleResponse(sent.getWellnessEventId(), "stop_today", userId);
        wellnessEventScheduleRepository.flush();

        WellnessEventSchedule reloadedSent = wellnessEventScheduleRepository.findById(sent.getWellnessEventId()).orElseThrow();
        WellnessEventSchedule reloadedFuture = wellnessEventScheduleRepository.findById(future.getWellnessEventId()).orElseThrow();
        assertThat(reloadedSent.getResponseAction()).isEqualTo("stop_today");
        assertThat(reloadedSent.getCancelledAt()).isNull();
        assertThat(reloadedFuture.getCancelledAt()).isNotNull();
        assertThat(reloadedFuture.getCancelReason()).isEqualTo("user_stop_today");
    }

    private void setUpEnrouteEventWithScore(UUID userId, Instant now, int wisScore) {
        Event event = eventRepository.save(Event.builder()
                .userId(userId).sourceType("internal").startsAt(now)
                .isAllDay(false).locationState("not_required").autoManageExcluded(false)
                .status("enroute").createdAt(now)
                .build());
        PlanRevision revision = planRevisionRepository.save(PlanRevision.builder()
                .eventId(event.getEventId()).revisionNo(1)
                .prepStartAt(now).recommendedDepartAt(now).targetArriveAt(now)
                .estimatedPrepMinutes(0).extraPrepMinutes(0).personalRoutineMinutes(0)
                .travelMinutes(0).trafficBufferMinutes(0).arrivalBufferMinutes(0)
                .feasible(true).reasons("[]").degraded("[]")
                .predictionConfidence("high").planStatus("active").calcVersion("test")
                .createdAt(now)
                .build());
        userSettingRepository.save(UserSetting.builder()
                .userId(userId).arrivalBufferMinutes(10).notificationSensitivity("normal")
                .personalizationEnabled(true).autoManageEnabled(true).wellnessEventEnabled(true)
                .lockscreenHideSensitive(true).updatedAt(now).build());
        planContextRepository.save(PlanContext.builder()
                .planId(revision.getPlanId()).estimatedOutdoorMinutes(15).build());
        planWellnessScoreRepository.save(PlanWellnessScore.builder()
                .planId(revision.getPlanId())
                .uvLoad(BigDecimal.ONE).pmLoad(BigDecimal.ZERO).thermalLoad(BigDecimal.ZERO)
                .outdoorLoad(BigDecimal.ONE).interestMultiplier(BigDecimal.ONE)
                .wisScore((short) wisScore).wisBand("high").weightVersion("w1")
                .calculatedAt(now)
                .build());
    }

    private UUID extractUserId(String accessToken) {
        String payload = accessToken.split("\\.")[1];
        String decoded = new String(java.util.Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        return UUID.fromString(JsonPath.read(decoded, "$.sub").toString());
    }

    private String signupAndLogin() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String signupBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated());

        String loginBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        String response = mockMvc.perform(post("/auth/email/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(response, "$.accessToken");
    }
}
