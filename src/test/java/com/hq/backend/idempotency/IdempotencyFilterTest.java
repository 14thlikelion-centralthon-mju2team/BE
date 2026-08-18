package com.hq.backend.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
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

// 필터가 실제 Spring MVC 요청 파이프라인에서 동작하는지 확인 — /consents를 테스트 도구로만
// 쓴다(이미 있는 엔드포인트라 준비가 간단해서). #28 결정(소급 적용 안 함)과는 별개로, 이건
// "필터 메커니즘 자체가 동작하는가"를 검증하는 것뿐이다.
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyFilterTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void 같은_Idempotency_Key로_두번_요청하면_같은_응답을_재생하고_리소스는_한번만_생긴다() throws Exception {
        String accessToken = signupAndLogin();
        String idempotencyKey = UUID.randomUUID().toString();
        String body = """
                {"consentType":"LOCATION","agreed":true,"policyVersion":"1.0.0"}
                """;

        String firstResponse = mockMvc.perform(post("/consents")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String firstId = JsonPath.read(firstResponse, "$.id").toString();

        String secondResponse = mockMvc.perform(post("/consents")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String secondId = JsonPath.read(secondResponse, "$.id").toString();

        // 진짜로 재처리됐다면 매번 새 id가 생긴다 — 같은 id가 나와야 재생된 것.
        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void Idempotency_Key가_다르면_별개_요청으로_처리된다() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"consentType":"LOCATION","agreed":true,"policyVersion":"1.0.0"}
                """;

        String firstResponse = mockMvc.perform(post("/consents")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String firstId = JsonPath.read(firstResponse, "$.id").toString();

        String secondResponse = mockMvc.perform(post("/consents")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String secondId = JsonPath.read(secondResponse, "$.id").toString();

        assertThat(secondId).isNotEqualTo(firstId);
    }

    @Test
    void Idempotency_Key_헤더가_없으면_400이다() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"consentType":"LOCATION","agreed":true,"policyVersion":"1.0.0"}
                """;

        mockMvc.perform(post("/consents")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    private String signupAndLogin() throws Exception {
        String email = "idem-filter-" + UUID.randomUUID() + "@example.com";
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
