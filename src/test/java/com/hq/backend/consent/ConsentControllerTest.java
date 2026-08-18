package com.hq.backend.consent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ConsentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 동의를_기록하면_201과_기록내용을_반환한다() throws Exception {
        String accessToken = signupAndLogin();
        String idempotencyKey = UUID.randomUUID().toString();
        String body = """
                {"consentType":"LOCATION","agreed":true,"policyVersion":"1.0.0"}
                """;

        mockMvc.perform(post("/consents")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.consentType").value("LOCATION"))
                .andExpect(jsonPath("$.agreed").value(true))
                .andExpect(jsonPath("$.recordedAt").exists());
    }

    @Test
    void 존재하지_않는_consent_type이면_프로젝트_표준_에러_포맷으로_400() throws Exception {
        String accessToken = signupAndLogin();
        String idempotencyKey = UUID.randomUUID().toString();
        String body = """
                {"consentType":"FOO","agreed":true,"policyVersion":"1.0.0"}
                """;

        mockMvc.perform(post("/consents")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void 인증_없이_요청하면_401() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String body = """
                {"consentType":"LOCATION","agreed":true,"policyVersion":"1.0.0"}
                """;

        mockMvc.perform(post("/consents")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
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
