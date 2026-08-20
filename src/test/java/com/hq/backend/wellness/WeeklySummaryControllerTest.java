package com.hq.backend.wellness;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.personalization.EventExecution;
import com.hq.backend.personalization.EventExecutionRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// 주간 리포트(CAL-06). 2026-08-17(월)~08-23(일) 주를 기준으로 검증한다.
@SpringBootTest
@AutoConfigureMockMvc
class WeeklySummaryControllerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PlanRevisionRepository planRevisionRepository;

    @Autowired
    private EventExecutionRepository eventExecutionRepository;

    @Test
    void 주중_아무_날짜를_줘도_KST_월요일부터의_주로_맞춘다() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(get("/summary/weekly")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-08-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekStart").value("2026-08-17"))
                .andExpect(jsonPath("$.weekEnd").value("2026-08-23"));
    }

    @Test
    void 관리_일정이_없으면_0건과_null_비율을_준다() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(get("/summary/weekly")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-08-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managedEventCount").value(0))
                .andExpect(jsonPath("$.onTimeRate").doesNotExist())
                .andExpect(jsonPath("$.averageSlackMinutes").doesNotExist())
                .andExpect(jsonPath("$.wellnessCompletionRate").doesNotExist())
                .andExpect(jsonPath("$.prepAccuracy").isEmpty());
    }

    @Test
    void 정시도착률은_on_time만_세고_unknown은_분모에서_뺀다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);

        // on_time / early / unknown 각 1건 — 분모 2, 분자 1
        execution(event(userId, "2026-08-17T14:00"), "on_time", null, null, null, null);
        execution(event(userId, "2026-08-18T14:00"), "early", null, null, null, null);
        execution(event(userId, "2026-08-19T14:00"), "unknown", null, null, null, null);

        mockMvc.perform(get("/summary/weekly")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-08-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managedEventCount").value(3))
                .andExpect(jsonPath("$.onTimeSampleCount").value(2))
                .andExpect(jsonPath("$.onTimeRate").value(0.5));
    }

    @Test
    void 취소된_일정은_관리_일정에서_빠진다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);
        event(userId, "2026-08-17T14:00");
        Event cancelled = event(userId, "2026-08-18T14:00");
        cancelled.setStatus("cancelled");
        eventRepository.saveAndFlush(cancelled);

        mockMvc.perform(get("/summary/weekly")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-08-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managedEventCount").value(1));
    }

    @Test
    void 평균_여유는_도착시각과_시작시각의_차이고_지각은_음수로_남는다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);

        // 10분 일찍 도착 / 4분 지각 → 평균 +3분
        Event early = event(userId, "2026-08-17T14:00");
        execution(early, "early", early.getStartsAt().minus(Duration.ofMinutes(10)), null, null, null);
        Event late = event(userId, "2026-08-18T14:00");
        execution(late, "late", late.getStartsAt().plus(Duration.ofMinutes(4)), null, null, null);

        mockMvc.perform(get("/summary/weekly")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-08-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageSlackSampleCount").value(2))
                .andExpect(jsonPath("$.averageSlackMinutes").value(3));
    }

    @Test
    void 준비시간_추이는_예측과_실제가_모두_있는_일정만_일별로_센다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);

        // 계획은 30분을 안내했고 실제로는 45분 걸렸다.
        Event measured = event(userId, "2026-08-17T14:00", 30);
        Instant prepStarted = measured.getStartsAt().minus(Duration.ofMinutes(90));
        execution(measured, "on_time", null, prepStarted, prepStarted.plus(Duration.ofMinutes(45)), null);
        // 실제 기록이 없는 일정은 추이에서 빠진다.
        execution(event(userId, "2026-08-18T14:00", 30), "on_time", null, null, null, null);

        mockMvc.perform(get("/summary/weekly")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-08-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prepAccuracy.length()").value(1))
                .andExpect(jsonPath("$.prepAccuracy[0].date").value("2026-08-17"))
                .andExpect(jsonPath("$.prepAccuracy[0].predictedMinutes").value(30))
                .andExpect(jsonPath("$.prepAccuracy[0].actualMinutes").value(45))
                .andExpect(jsonPath("$.prepAccuracy[0].sampleCount").value(1));
    }

    @Test
    void 야외노출은_실측이_있으면_observed고_합산된다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);
        execution(event(userId, "2026-08-17T14:00"), "on_time", null, null, null, 45);
        execution(event(userId, "2026-08-18T14:00"), "on_time", null, null, null, 20);

        mockMvc.perform(get("/summary/weekly")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-08-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outdoorMinutes").value(65))
                .andExpect(jsonPath("$.outdoorSampleCount").value(2))
                .andExpect(jsonPath("$.outdoorSource").value("observed"));
    }

    @Test
    void 지난주_일정은_이번주_집계에_들어가지_않는다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);
        event(userId, "2026-08-16T14:00"); // 전 주 일요일
        event(userId, "2026-08-17T14:00"); // 이번 주 월요일
        event(userId, "2026-08-24T14:00"); // 다음 주 월요일

        mockMvc.perform(get("/summary/weekly")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-08-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managedEventCount").value(1));
    }

    private Event event(UUID userId, String localDateTime) {
        return event(userId, localDateTime, 0);
    }

    private Event event(UUID userId, String localDateTime, int plannedPrepMinutes) {
        Instant startsAt = LocalDate.parse(localDateTime.substring(0, 10))
                .atTime(Integer.parseInt(localDateTime.substring(11, 13)), 0)
                .atZone(KST).toInstant();
        Event event = eventRepository.save(Event.builder()
                .userId(userId).sourceType("internal").startsAt(startsAt)
                .isAllDay(false).locationState("not_required").autoManageExcluded(false)
                .status("closed").createdAt(startsAt)
                .build());
        Instant departAt = startsAt.minus(Duration.ofMinutes(60));
        planRevisionRepository.save(PlanRevision.builder()
                .eventId(event.getEventId()).revisionNo(1)
                .prepStartAt(departAt.minus(Duration.ofMinutes(plannedPrepMinutes)))
                .recommendedDepartAt(departAt).targetArriveAt(startsAt)
                .estimatedPrepMinutes(plannedPrepMinutes).extraPrepMinutes(0).personalRoutineMinutes(0)
                .travelMinutes(0).trafficBufferMinutes(0).arrivalBufferMinutes(0)
                .feasible(true).reasons("[]").degraded("[]")
                .predictionConfidence("high").planStatus("active").calcVersion("test")
                .createdAt(startsAt)
                .build());
        return event;
    }

    private void execution(Event event, String arrivalResult, Instant arrivedAt,
            Instant prepStartedAt, Instant prepFinishedAt, Integer outdoorMinutes) {
        eventExecutionRepository.save(EventExecution.builder()
                .eventId(event.getEventId())
                .arrivalResult(arrivalResult).resultSource("user")
                .actualArrivedAt(arrivedAt)
                .actualPrepStartedAt(prepStartedAt).actualPrepFinishedAt(prepFinishedAt)
                .actualOutdoorMinutes(outdoorMinutes)
                .createdAt(event.getStartsAt()).updatedAt(event.getStartsAt())
                .build());
    }

    private String signupAndLogin() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String body = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        String response = mockMvc.perform(post("/auth/email/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    private UUID extractUserId(String accessToken) {
        String payload = accessToken.split("\\.")[1];
        String decoded = new String(java.util.Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        return UUID.fromString(JsonPath.read(decoded, "$.sub").toString());
    }
}
