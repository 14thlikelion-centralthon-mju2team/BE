package com.hq.backend.event;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventClassificationReviewRepository eventClassificationReviewRepository;

    @Test
    void 일정을_생성하면_201과_displayLabel을_표시명으로_반환한다() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"startsAt":"2026-08-20T14:00:00+09:00","endsAt":"2026-08-20T15:00:00+09:00",
                 "locationState":"NOT_REQUIRED","sourceType":"INTERNAL","displayLabel":"강남역 미팅"}
                """;

        mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("강남역 미팅"))
                .andExpect(jsonPath("$.locationState").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.plan").value(nullValue()));
    }

    @Test
    void displayLabel이_없으면_destinationName으로_표시명을_대체한다() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"startsAt":"2026-08-20T14:00:00+09:00","locationState":"REQUIRED_RESOLVED",
                 "sourceType":"MAP_SEARCH","destinationName":"강남역"}
                """;

        mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("강남역"));
    }

    @Test
    void 조회_수정_삭제가_소유자_기준으로_동작한다() throws Exception {
        String accessToken = signupAndLogin();
        String eventId = createEvent(accessToken, "2026-08-21T10:00:00+09:00");

        mockMvc.perform(get("/events/" + eventId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId));

        mockMvc.perform(patch("/events/" + eventId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locationState":"REQUIRED_MISSING"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationState").value("REQUIRED_MISSING"));

        mockMvc.perform(delete("/events/" + eventId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/events/" + eventId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void 다른_사용자의_일정에_접근하면_404() throws Exception {
        String ownerToken = signupAndLogin();
        String eventId = createEvent(ownerToken, "2026-08-22T10:00:00+09:00");
        String otherToken = signupAndLogin();

        mockMvc.perform(get("/events/" + eventId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("EVENT_NOT_FOUND"));
    }

    @Test
    void destinationLat만_지정하고_Lng를_비우면_422() throws Exception {
        String accessToken = signupAndLogin();
        String eventId = createEvent(accessToken, "2026-08-23T10:00:00+09:00");

        mockMvc.perform(patch("/events/" + eventId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"destinationLat":37.498}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void 생성시_destinationLat만_지정하고_Lng를_비우면_422() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"startsAt":"2026-08-24T10:00:00+09:00","locationState":"NOT_REQUIRED",
                 "sourceType":"INTERNAL","destinationLat":37.498}
                """;

        mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void endsAt이_startsAt보다_빠르면_422() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"startsAt":"2026-08-24T14:00:00+09:00","endsAt":"2026-08-24T13:00:00+09:00",
                 "locationState":"NOT_REQUIRED","sourceType":"INTERNAL"}
                """;

        mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void 취소된_일정은_다음_일정으로_뜨지_않는다() throws Exception {
        String accessToken = signupAndLogin();
        String eventId = createEvent(accessToken, "2099-01-01T10:00:00+09:00");

        mockMvc.perform(delete("/events/" + eventId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/events/next").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NEXT_EVENT_NOT_FOUND"));
    }

    @Test
    void 분류_확인_응답에_online_offline이_아닌_값을_보내면_422() throws Exception {
        String accessToken = signupAndLogin();
        String eventId = createEvent(accessToken, "2026-08-25T10:00:00+09:00");
        eventClassificationReviewRepository.save(EventClassificationReview.builder()
                .eventId(UUID.fromString(eventId))
                .questionType("is_online")
                .titleSnapshot("점심 약속")
                .askedAt(Instant.now())
                .build());

        mockMvc.perform(post("/events/" + eventId + "/review")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"questionType":"is_online","userAnswer":"글쎄요"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void 기간_조회는_범위_안의_일정만_반환한다() throws Exception {
        String accessToken = signupAndLogin();
        String insideId = createEvent(accessToken, "2026-09-01T10:00:00+09:00");
        createEvent(accessToken, "2026-10-01T10:00:00+09:00");

        mockMvc.perform(get("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("from", "2026-08-31T00:00:00+09:00")
                        .param("to", "2026-09-02T00:00:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].event_id").value(insideId));
    }

    private String createEvent(String accessToken, String startsAt) throws Exception {
        String body = """
                {"startsAt":"%s","locationState":"NOT_REQUIRED","sourceType":"INTERNAL"}
                """.formatted(startsAt);
        String response = mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.event_id");
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
