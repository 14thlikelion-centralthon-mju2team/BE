package com.hq.backend.plan;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hq.backend.place.PlaceCoordinateCodec;
import com.hq.backend.place.UserPlace;
import com.hq.backend.place.UserPlaceRepository;
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

// API 명세 §13 POST /plans/{planId}/actions.
@SpringBootTest
@AutoConfigureMockMvc
class PlanActionControllerTest {

    private static HttpServer fakeEngine;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserPlaceRepository userPlaceRepository;

    @Autowired
    private PlaceCoordinateCodec placeCoordinateCodec;

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
                     "reasons":[],"checklist":[],
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
    void 준비시작과_출발_액션을_보내면_일정_상태가_enroute까지_전이되고_계획이_동봉된다() throws Exception {
        Created created = createEventWithPlan();

        String body = """
                {"actions":[
                  {"action_type":"PREP_STARTED","action_source":"USER",
                   "device_ts":"2026-08-20T12:26:00+09:00","client_event_id":"%s"},
                  {"action_type":"DEPARTED","action_source":"GEO",
                   "device_ts":"2026-08-20T13:06:00+09:00","client_event_id":"%s","confidence":0.8}
                ]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/plans/" + created.planId() + "/actions")
                        .header("Authorization", "Bearer " + created.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.duplicated").value(0))
                .andExpect(jsonPath("$.event_status").value("enroute"))
                .andExpect(jsonPath("$.plan.plan_id").value(created.planId().toString()));
    }

    @Test
    void 같은_clientEventId를_재전송하면_duplicated로_흡수된다() throws Exception {
        Created created = createEventWithPlan();
        String clientEventId = UUID.randomUUID().toString();
        String body = """
                {"actions":[{"action_type":"SNOOZED","action_source":"USER",
                  "device_ts":"2026-08-20T12:26:00+09:00","client_event_id":"%s"}]}
                """.formatted(clientEventId);

        mockMvc.perform(post("/plans/" + created.planId() + "/actions")
                        .header("Authorization", "Bearer " + created.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));

        mockMvc.perform(post("/plans/" + created.planId() + "/actions")
                        .header("Authorization", "Bearer " + created.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.duplicated").value(1));
    }

    @Test
    void 다른_사용자의_계획에_액션을_보내면_404() throws Exception {
        Created created = createEventWithPlan();
        String otherToken = signupAndLogin();
        String body = """
                {"actions":[{"action_type":"SNOOZED","action_source":"USER",
                  "device_ts":"2026-08-20T12:26:00+09:00","client_event_id":"%s"}]}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/plans/" + created.planId() + "/actions")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAN_NOT_FOUND"));
    }

    @Test
    void 도착_액션을_보내면_실행_결과가_생성되고_조회된다() throws Exception {
        Created created = createEventWithPlan();

        String body = """
                {"actions":[
                  {"action_type":"PREP_STARTED","action_source":"USER",
                   "device_ts":"2026-08-20T12:26:00+09:00","client_event_id":"%s"},
                  {"action_type":"DEPARTED","action_source":"USER",
                   "device_ts":"2026-08-20T13:06:00+09:00","client_event_id":"%s"},
                  {"action_type":"ARRIVED","action_source":"GEO",
                   "device_ts":"2026-08-20T13:50:00+09:00","client_event_id":"%s","confidence":0.9}
                ]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/plans/" + created.planId() + "/actions")
                        .header("Authorization", "Bearer " + created.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(3))
                .andExpect(jsonPath("$.event_status").value("arrived"));

        mockMvc.perform(get("/events/" + created.eventId() + "/execution")
                        .header("Authorization", "Bearer " + created.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actual_prep_started_at").value("2026-08-20T03:26:00Z"))
                .andExpect(jsonPath("$.actual_departed_at").value("2026-08-20T04:06:00Z"))
                .andExpect(jsonPath("$.actual_arrived_at").value("2026-08-20T04:50:00Z"))
                .andExpect(jsonPath("$.arrival_result").value("ON_TIME"))
                .andExpect(jsonPath("$.result_source").value("geo"));
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
                {"starts_at":"2026-08-20T14:00:00+09:00","location_state":"REQUIRED_RESOLVED",
                 "source_type":"MAP_SEARCH","destination_name":"강남역",
                 "destination_lat":37.498,"destination_lng":127.027,
                 "origin_place_id":"%s"}
                """.formatted(origin.getPlaceId());

        String response = mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID eventId = UUID.fromString(JsonPath.read(response, "$.event_id").toString());
        UUID planId = UUID.fromString(JsonPath.read(response, "$.plan.plan_id").toString());
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

        return JsonPath.read(response, "$.access_token");
    }
}
