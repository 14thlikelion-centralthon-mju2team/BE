package com.hq.backend.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.plan.RouteOptionRepository;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

@SpringBootTest
@AutoConfigureMockMvc
class RouteSearchFlowIntegrationTest {

    private static HttpServer fakeEngine;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RouteSearchOptionRepository routeSearchOptionRepository;

    @Autowired
    private PlanRevisionRepository planRevisionRepository;

    @Autowired
    private RouteOptionRepository routeOptionRepository;

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
                     "reasons":[],"checklist":[],"feasible":true,
                     "predictionConfidence":"high","degraded":[],"calcVersion":"route-search-test"}
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
    void 검색_후_선택한_임시후보가_계획의_ROUTE_OPTION으로_확정된다() throws Exception {
        String token = signupAndLogin();
        String temporaryOptionId = search(token);

        String response = mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventRequest(temporaryOptionId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plan.planId").exists())
                .andReturn().getResponse().getContentAsString();

        UUID planId = UUID.fromString(JsonPath.read(response, "$.plan.planId").toString());
        PlanRevision plan = planRevisionRepository.findById(planId).orElseThrow();
        assertThat(plan.getSelectedRouteOptionId()).isNotNull();
        assertThat(routeOptionRepository.findByPlanIdOrderByRouteRankAsc(planId))
                .singleElement()
                .satisfies(route -> {
                    assertThat(route.getRouteOptionId()).isEqualTo(plan.getSelectedRouteOptionId());
                    assertThat(route.getRouteType()).isEqualTo("fastest");
                    assertThat(route.getTotalMinutes()).isEqualTo(20);
                });
        assertThat(routeSearchOptionRepository.findById(UUID.fromString(temporaryOptionId)).orElseThrow().getConsumedPlanId())
                .isEqualTo(planId);
    }

    @Test
    void 잘못된_목적지_좌표는_검색_전에_422로_거절된다() throws Exception {
        String token = signupAndLogin();
        mockMvc.perform(get("/routes/search")
                        .header("Authorization", "Bearer " + token)
                        .param("originLat", "37.5").param("originLng", "127.0")
                        .param("destLat", "91").param("destLng", "127.027")
                        .param("at", "2026-08-20T14:00:00+09:00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 다른_사용자의_임시후보는_일정_생성에_사용할_수_없다() throws Exception {
        String ownerToken = signupAndLogin();
        String temporaryOptionId = search(ownerToken);
        String otherToken = signupAndLogin();

        mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventRequest(temporaryOptionId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROUTE_OPTION_NOT_FOUND"));
    }

    @Test
    void 만료된_임시후보는_재검색을_요구한다() throws Exception {
        String token = signupAndLogin();
        String temporaryOptionId = search(token);
        RouteSearchOption option = routeSearchOptionRepository.findById(UUID.fromString(temporaryOptionId)).orElseThrow();
        option.setCreatedAt(Instant.now().minusSeconds(3600));
        option.setExpiresAt(Instant.now().minusSeconds(1));
        routeSearchOptionRepository.save(option);

        mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventRequest(temporaryOptionId)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("ROUTE_OPTION_EXPIRED"));
    }

    private String search(String token) throws Exception {
        String response = mockMvc.perform(get("/routes/search")
                        .header("Authorization", "Bearer " + token)
                        .param("originLat", "37.500").param("originLng", "127.000")
                        .param("destLat", "37.498").param("destLng", "127.027")
                        .param("destName", "강남역").param("anchorMode", "arrive_by")
                        .param("at", "2026-08-20T14:00:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].routeType").value("fastest"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$[0].routeOptionId");
    }

    private String eventRequest(String routeOptionId) {
        return """
                {"startsAt":"2026-08-20T14:00:00+09:00","locationState":"REQUIRED_RESOLVED",
                 "sourceType":"MAP_SEARCH","destinationName":"강남역",
                 "destinationLat":37.498,"destinationLng":127.027,
                 "anchorMode":"arrive_by","selectedRouteOptionId":"%s"}
                """.formatted(routeOptionId);
    }

    private String signupAndLogin() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"securePassword123\"}".formatted(email)))
                .andExpect(status().isCreated());
        String response = mockMvc.perform(post("/auth/email/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"securePassword123\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }
}
