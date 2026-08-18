package com.hq.backend.plan;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hq.backend.place.PlaceCoordinateCodec;
import com.hq.backend.place.UserPlace;
import com.hq.backend.place.UserPlaceRepository;
import com.hq.backend.wellness.PlanWellnessAction;
import com.hq.backend.wellness.PlanWellnessActionRepository;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

// API 명세 §12.2 — 준비 항목/웰니스 행동 resolve 두 경로.
@SpringBootTest
@AutoConfigureMockMvc
class PlanResolveControllerTest {

    private static HttpServer fakeEngine;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserPlaceRepository userPlaceRepository;

    @Autowired
    private PlaceCoordinateCodec placeCoordinateCodec;

    @Autowired
    private PlanWellnessActionRepository planWellnessActionRepository;

    @BeforeAll
    static void startFakeEngine() throws IOException {
        fakeEngine = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeEngine.createContext("/internal/v1/plans/compute", exchange -> {
            byte[] body = """
                    {"prepStartAt":"2026-08-20T12:25:00+09:00",
                     "recommendedDepartAt":"2026-08-20T13:05:00+09:00",
                     "targetArriveAt":"2026-08-20T13:50:00+09:00",
                     "breakdown":{"estimatedPrepMinutes":30,"extraPrepMinutes":0,
                       "personalRoutineMinutes":0,"travelMinutes":20,
                       "trafficBufferMinutes":5,"arrivalBufferMinutes":10},
                     "reasons":[],
                     "checklist":[{"itemName":"우산","actionType":"carry","sourceType":"weather",
                       "appliedMinutes":0,"isSensitive":false,"reason":"강수 확률 높음"}],
                     "feasible":true,"predictionConfidence":"high","degraded":[],
                     "calcVersion":"test-engine-1.0.0"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        fakeEngine.start();
    }

    @AfterAll
    static void stopFakeEngine() {
        fakeEngine.stop(0);
    }

    @DynamicPropertySource
    static void planEngineUrl(DynamicPropertyRegistry registry) {
        registry.add("plan-engine.base-url", () -> "http://localhost:" + fakeEngine.getAddress().getPort());
    }

    @Test
    void 준비_항목을_완료_처리하면_completedAt이_기록된다() throws Exception {
        Created created = createEventWithPlan();
        String planResponse = mockMvc.perform(get("/plans/" + created.planId())
                        .header("Authorization", "Bearer " + created.accessToken()))
                .andReturn().getResponse().getContentAsString();
        String planPrepItemId = JsonPath.read(planResponse, "$.checklist[0].planPrepItemId");

        mockMvc.perform(post("/plans/" + created.planId() + "/prep-items/" + planPrepItemId + "/resolve")
                        .header("Authorization", "Bearer " + created.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completionStatus\":\"COMPLETED\",\"clientEventId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completionStatus").value("completed"))
                .andExpect(jsonPath("$.completedAt").exists());
    }

    @Test
    void 다른_사용자의_준비_항목은_404() throws Exception {
        Created created = createEventWithPlan();
        String planResponse = mockMvc.perform(get("/plans/" + created.planId())
                        .header("Authorization", "Bearer " + created.accessToken()))
                .andReturn().getResponse().getContentAsString();
        String planPrepItemId = JsonPath.read(planResponse, "$.checklist[0].planPrepItemId");
        String otherToken = signupAndLogin();

        mockMvc.perform(post("/plans/" + created.planId() + "/prep-items/" + planPrepItemId + "/resolve")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completionStatus\":\"COMPLETED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PLAN_NOT_FOUND"));
    }

    @Test
    void 웰니스_행동을_완료_처리하면_상태와_응답시각이_반영된다() throws Exception {
        Created created = createEventWithPlan();
        PlanWellnessAction action = planWellnessActionRepository.save(PlanWellnessAction.builder()
                .planId(created.planId())
                .wellnessTopic("uv")
                .actionCode("sunscreen")
                .actionLabel("출발 전 선크림 확인")
                .displayRank((short) 1)
                .reasonSnapshot("자외선 높음")
                .completionStatus("proposed")
                .build());

        mockMvc.perform(post("/plans/" + created.planId() + "/wellness-actions/" + action.getWellnessActionId() + "/resolve")
                        .header("Authorization", "Bearer " + created.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completionStatus\":\"DISMISSED\",\"clientEventId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completionStatus").value("dismissed"))
                .andExpect(jsonPath("$.respondedAt").exists());
    }

    @Test
    void 다른_사용자의_웰니스_행동은_404() throws Exception {
        Created created = createEventWithPlan();
        PlanWellnessAction action = planWellnessActionRepository.save(PlanWellnessAction.builder()
                .planId(created.planId())
                .wellnessTopic("uv")
                .actionCode("sunscreen")
                .actionLabel("출발 전 선크림 확인")
                .displayRank((short) 1)
                .reasonSnapshot("자외선 높음")
                .completionStatus("proposed")
                .build());
        String otherToken = signupAndLogin();

        mockMvc.perform(post("/plans/" + created.planId() + "/wellness-actions/" + action.getWellnessActionId() + "/resolve")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completionStatus\":\"COMPLETED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PLAN_NOT_FOUND"));
    }

    private record Created(String accessToken, UUID eventId, UUID planId) {
    }

    private Created createEventWithPlan() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);

        UserPlace origin = userPlaceRepository.save(UserPlace.builder()
                .userId(userId)
                .placeType("home")
                .placeName("집")
                .address("서울시 어딘가")
                .latEnc(placeCoordinateCodec.encode(37.5))
                .lngEnc(placeCoordinateCodec.encode(127.0))
                .isPrimary(true)
                .build());

        String body = """
                {"startsAt":"2026-08-20T14:00:00+09:00","locationState":"REQUIRED_RESOLVED",
                 "sourceType":"MAP_SEARCH","destinationName":"강남역",
                 "destinationLat":37.498,"destinationLng":127.027,
                 "originPlaceId":"%s"}
                """.formatted(origin.getPlaceId());

        String response = mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID eventId = UUID.fromString(JsonPath.read(response, "$.eventId").toString());
        UUID planId = UUID.fromString(JsonPath.read(response, "$.plan.planId").toString());
        return new Created(accessToken, eventId, planId);
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
