package com.hq.backend.auth;

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

// API 명세 §2.2·§2.9. consentRequired가 빈 배열이 될 때까지 클라이언트가 홈 진입을 막는다.
@SpringBootTest
@AutoConfigureMockMvc
class LoginConsentRequiredTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 동의_기록이_없으면_필수_약관_세개가_consentRequired로_내려간다() throws Exception {
        String email = signup();

        login(email)
                .andExpect(jsonPath("$.consentRequired.length()").value(3))
                .andExpect(jsonPath("$.consentRequired[0]").value("terms"))
                .andExpect(jsonPath("$.consentRequired[1]").value("privacy"))
                .andExpect(jsonPath("$.consentRequired[2]").value("location"));
    }

    @Test
    void 필수_약관을_모두_동의하면_consentRequired가_비고_marketing은_영향이_없다() throws Exception {
        String email = signup();
        String accessToken = accessToken(email);

        agree(accessToken, "terms", "v1", true);
        agree(accessToken, "privacy", "v1", true);
        agree(accessToken, "location", "v1", true);
        agree(accessToken, "marketing", "v1", false);

        login(email).andExpect(jsonPath("$.consentRequired.length()").value(0));
    }

    @Test
    void 동의를_철회하면_다시_consentRequired에_들어간다() throws Exception {
        String email = signup();
        String accessToken = accessToken(email);
        agree(accessToken, "terms", "v1", true);
        agree(accessToken, "privacy", "v1", true);
        agree(accessToken, "location", "v1", true);

        agree(accessToken, "privacy", "v1", false);

        login(email)
                .andExpect(jsonPath("$.consentRequired.length()").value(1))
                .andExpect(jsonPath("$.consentRequired[0]").value("privacy"));
    }

    @Test
    void 지난_정책_버전의_동의는_재동의_대상이다() throws Exception {
        String email = signup();
        String accessToken = accessToken(email);
        agree(accessToken, "terms", "v0", true);
        agree(accessToken, "privacy", "v1", true);
        agree(accessToken, "location", "v1", true);

        login(email)
                .andExpect(jsonPath("$.consentRequired.length()").value(1))
                .andExpect(jsonPath("$.consentRequired[0]").value("terms"));
    }

    private String signup() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/auth/email/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email)))
                .andExpect(status().isCreated());
        return email;
    }

    private org.springframework.test.web.servlet.ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/auth/email/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email)))
                .andExpect(status().isOk());
    }

    private String accessToken(String email) throws Exception {
        return JsonPath.read(login(email).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private void agree(String accessToken, String type, String policyVersion, boolean agreed) throws Exception {
        mockMvc.perform(post("/consents")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentType":"%s","agreed":%s,"policyVersion":"%s"}
                                """.formatted(type, agreed, policyVersion)))
                .andExpect(status().isCreated());
    }

    private String credentials(String email) {
        return """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
    }
}
