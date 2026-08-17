package com.hq.backend.personalization;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                .andExpect(jsonPath("$.estimates[0].estimated_minutes").value(35))
                .andExpect(jsonPath("$.traffic_buffer_minutes").exists());
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

        return JsonPath.read(response, "$.access_token");
    }
}
