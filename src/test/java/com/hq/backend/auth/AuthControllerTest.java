package com.hq.backend.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void signupThenLoginIssuesToken() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String signupBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);

        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email));

        String loginBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);

        mockMvc.perform(post("/auth/email/login").contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.expires_in").value(3600));
    }

    @Test
    void signupDuplicateEmailIsRejected() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String body = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);

        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_EXISTS"));
    }

    @Test
    void 연속_5회_로그인_실패하면_계정이_잠긴다() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String signupBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated());

        String wrongLoginBody = """
                {"email":"%s","password":"wrongPassword123"}
                """.formatted(email);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/auth/email/login").contentType(MediaType.APPLICATION_JSON).content(wrongLoginBody))
                    .andExpect(status().isUnauthorized());
        }

        String correctLoginBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        mockMvc.perform(post("/auth/email/login").contentType(MediaType.APPLICATION_JSON).content(correctLoginBody))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("ACCOUNT_LOCKED"));
    }
}
