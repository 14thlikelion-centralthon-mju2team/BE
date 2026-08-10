package com.hq.backend.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
                {"email":"%s","password":"securePassword123","birth_date":"1995-05-15"}
                """.formatted(email);

        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.age_confirmed").value(true));

        String loginBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);

        mockMvc.perform(post("/auth/email/login").contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.expires_in").value(3600));
    }

    @Test
    void signupUnderAgeIsRejected() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        LocalDate underAgeBirth = LocalDate.now().minusYears(10);
        String body = """
                {"email":"%s","password":"securePassword123","birth_date":"%s"}
                """.formatted(email, underAgeBirth);

        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("UNDER_AGE"));
    }

    @Test
    void signupDuplicateEmailIsRejected() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String body = """
                {"email":"%s","password":"securePassword123","birth_date":"1995-05-15"}
                """.formatted(email);

        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_EXISTS"));
    }
}
