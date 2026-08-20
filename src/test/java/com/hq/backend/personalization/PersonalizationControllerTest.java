package com.hq.backend.personalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hq.backend.metrics.ProductEventRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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
class PersonalizationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserPrepEstimateRepository userPrepEstimateRepository;

    @Autowired
    private ProductEventRepository productEventRepository;

    @Test
    void 개인화_추정값을_조회한다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = UUID.fromString(JsonPath.read(decode(accessToken), "$.sub").toString());

        userPrepEstimateRepository.save(UserPrepEstimate.builder()
                .userId(userId)
                .scopeType("global")
                .estimatedMinutes(35)
                .sampleCount(8)
                .confidence(new BigDecimal("0.71"))
                .modelVersion("m1")
                .adjustmentReason("저녁 약속에서 평균 12분 늦게 출발")
                .validFrom(Instant.now())
                .build());

        mockMvc.perform(get("/me/personalization").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimates[0].estimatedMinutes").value(35))
                .andExpect(jsonPath("$.trafficBufferMinutes").exists());
    }

    @Test
    void 초기화하면_추정값이_더이상_조회되지_않는다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = UUID.fromString(JsonPath.read(decode(accessToken), "$.sub").toString());

        userPrepEstimateRepository.save(UserPrepEstimate.builder()
                .userId(userId)
                .scopeType("global")
                .estimatedMinutes(35)
                .sampleCount(8)
                .confidence(new BigDecimal("0.71"))
                .modelVersion("m1")
                .validFrom(Instant.now())
                .build());

        mockMvc.perform(delete("/me/personalization").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/me/personalization").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimates.length()").value(0));

        boolean logged = productEventRepository.findAll().stream()
                .anyMatch(e -> "personalization_reset".equals(e.getEventName()) && userId.equals(e.getUserId()));
        assertThat(logged).isTrue();
    }

    @Test
    void eventId_없이_되돌리면_직전_global_추정값으로_복원된다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = UUID.fromString(JsonPath.read(decode(accessToken), "$.sub").toString());
        Instant now = Instant.now();

        userPrepEstimateRepository.save(UserPrepEstimate.builder()
                .userId(userId).scopeType("global").estimatedMinutes(30).sampleCount(5)
                .confidence(new BigDecimal("0.60")).modelVersion("m1")
                .validFrom(now.minusSeconds(3600)).validTo(now)
                .build());
        userPrepEstimateRepository.save(UserPrepEstimate.builder()
                .userId(userId).scopeType("global").estimatedMinutes(42).sampleCount(6)
                .confidence(new BigDecimal("0.65")).modelVersion("m1")
                .adjustmentReason("최근 지연 반영").validFrom(now)
                .build());

        mockMvc.perform(post("/me/personalization/revert")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimates[0].estimatedMinutes").value(30));

        boolean logged = productEventRepository.findAll().stream()
                .anyMatch(e -> "personalization_reverted".equals(e.getEventName())
                        && userId.equals(e.getUserId()));
        assertThat(logged).isTrue();
    }

    @Test
    void 되돌릴_이전_보정이_없으면_409() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = UUID.fromString(JsonPath.read(decode(accessToken), "$.sub").toString());
        Instant now = Instant.now();

        userPrepEstimateRepository.save(UserPrepEstimate.builder()
                .userId(userId).scopeType("global").estimatedMinutes(30).sampleCount(0)
                .confidence(BigDecimal.ZERO).modelVersion("m1").validFrom(now)
                .build());
        mockMvc.perform(post("/me/personalization/revert")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NO_ADJUSTMENT_TO_REVERT"));
    }

    private String decode(String jwt) {
        String payload = jwt.split("\\.")[1];
        return new String(java.util.Base64.getUrlDecoder().decode(payload));
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
