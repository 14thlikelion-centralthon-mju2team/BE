package com.hq.backend.calendar;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// API 명세 §7. FE(calendar_screen)는 status의 connected만 보고 수동 동기화 여부를 정한다.
@SpringBootTest
@AutoConfigureMockMvc
class CalendarStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CalendarConnectionRepository calendarConnectionRepository;

    @Test
    void 연결이_없으면_connected는_false다() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(get("/calendar/google/status").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.connectedAt").doesNotExist());
    }

    @Test
    void 활성_연결이_있으면_connected와_계정정보를_준다() throws Exception {
        String accessToken = signupAndLogin();
        Instant connectedAt = Instant.parse("2026-08-20T01:00:00Z");
        calendarConnectionRepository.save(CalendarConnection.builder()
                .userId(extractUserId(accessToken))
                .provider("google")
                .externalAccountId("google-sub-1")
                .connectedAt(connectedAt)
                .build());

        mockMvc.perform(get("/calendar/google/status").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.provider").value("google"))
                .andExpect(jsonPath("$.externalAccountId").value("google-sub-1"));
    }

    @Test
    void 해지된_연결은_connected가_false다() throws Exception {
        String accessToken = signupAndLogin();
        calendarConnectionRepository.save(CalendarConnection.builder()
                .userId(extractUserId(accessToken))
                .provider("google")
                .externalAccountId("google-sub-2")
                .connectedAt(Instant.parse("2026-08-20T01:00:00Z"))
                .revokedAt(Instant.parse("2026-08-20T02:00:00Z"))
                .build());

        mockMvc.perform(get("/calendar/google/status").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void 연결이_없는_상태의_수동동기화는_404다() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(post("/calendar/sync").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CALENDAR_NOT_CONNECTED"));
    }

    private String signupAndLogin() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String body = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        String response = mockMvc.perform(post("/auth/email/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    private UUID extractUserId(String accessToken) {
        String payload = accessToken.split("\\.")[1];
        String decoded = new String(java.util.Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        return UUID.fromString(JsonPath.read(decoded, "$.sub").toString());
    }
}
