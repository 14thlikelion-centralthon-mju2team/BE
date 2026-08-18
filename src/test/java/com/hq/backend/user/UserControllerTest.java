package com.hq.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hq.backend.consent.UserConsentRepository;
import com.hq.backend.metrics.ProductEventRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCredentialRepository userCredentialRepository;

    @Autowired
    private UserConsentRepository userConsentRepository;

    @Autowired
    private ProductEventRepository productEventRepository;

    @Test
    void 탈퇴하면_사용자_데이터가_하드_삭제되고_동의_이력만_남는다() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String signupBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        String signupResponse = mockMvc.perform(post("/auth/email/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID userId = UUID.fromString(JsonPath.read(signupResponse, "$.id"));

        String loginBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        String loginResponse = mockMvc.perform(post("/auth/email/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String accessToken = JsonPath.read(loginResponse, "$.access_token");

        mockMvc.perform(post("/consents")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"TERMS","agreed":true,"policyVersion":"1.0.0"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").isArray())
                .andExpect(jsonPath("$.deleted", org.hamcrest.Matchers.hasItem("events")))
                .andExpect(jsonPath("$.retained", org.hamcrest.Matchers.hasItem("consentHistory")));

        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(userCredentialRepository.findById(userId)).isEmpty();

        List<com.hq.backend.consent.UserConsent> remainingConsents = userConsentRepository.findAll().stream()
                .filter(c -> c.getUserId() == null)
                .toList();
        assertThat(remainingConsents).isNotEmpty();

        // user_withdrawn은 탈퇴 CASCADE로 자기 로그가 같이 삭제되지 않도록 userId=null로 남는다.
        boolean withdrawnEventLogged = productEventRepository.findAll().stream()
                .anyMatch(e -> "user_withdrawn".equals(e.getEventName()) && e.getUserId() == null);
        assertThat(withdrawnEventLogged).isTrue();
    }

    @Test
    void 탈퇴한_이메일로_다시_가입할_수_있다() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String signupBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated());

        String loginResponse = mockMvc.perform(post("/auth/email/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String accessToken = JsonPath.read(loginResponse, "$.access_token");

        mockMvc.perform(delete("/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated());
    }
}
