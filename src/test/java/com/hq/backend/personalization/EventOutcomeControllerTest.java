package com.hq.backend.personalization;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class EventOutcomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventExecutionRepository eventExecutionRepository;

    @Autowired
    private EventDelayReasonRepository eventDelayReasonRepository;

    @Test
    void 실행_결과가_없으면_404() throws Exception {
        String accessToken = signupAndLogin();
        String eventId = createEvent(accessToken);

        mockMvc.perform(get("/events/" + eventId + "/execution").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("EXECUTION_NOT_FOUND"));
    }

    @Test
    void 실행_결과와_지연사유를_조회한다() throws Exception {
        String accessToken = signupAndLogin();
        String eventIdString = createEvent(accessToken);
        UUID eventId = UUID.fromString(eventIdString);

        eventExecutionRepository.save(EventExecution.builder()
                .eventId(eventId)
                .arrivalResult("late")
                .resultSource("geo")
                .actualOutdoorMinutes(47)
                .rushLoadScore((short) 22)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        eventDelayReasonRepository.save(EventDelayReason.builder()
                .eventId(eventId)
                .reasonCode("prep_late")
                .reasonSource("inferred")
                .confidence(new java.math.BigDecimal("0.72"))
                .createdAt(Instant.now())
                .build());

        mockMvc.perform(get("/events/" + eventIdString + "/execution").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrival_result").value("LATE"))
                .andExpect(jsonPath("$.delay_reasons[0].reason_code").value("prep_late"));
    }

    @Test
    void 피드백을_제출하면_저장되고_재제출하면_갱신된다() throws Exception {
        String accessToken = signupAndLogin();
        String eventId = createEvent(accessToken);

        mockMvc.perform(post("/events/" + eventId + "/feedback")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prep_timing_assessment":"TOO_EARLY","arrival_result":"ON_TIME","rush_assessment":"NOT_RUSHED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prep_timing_assessment").value("TOO_EARLY"));

        mockMvc.perform(post("/events/" + eventId + "/feedback")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prep_timing_assessment":"APPROPRIATE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prep_timing_assessment").value("APPROPRIATE"))
                .andExpect(jsonPath("$.arrival_result").value(nullValue()));
    }

    private String createEvent(String accessToken) throws Exception {
        String body = """
                {"starts_at":"2026-08-20T14:00:00+09:00","location_state":"NOT_REQUIRED","source_type":"INTERNAL"}
                """;
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
