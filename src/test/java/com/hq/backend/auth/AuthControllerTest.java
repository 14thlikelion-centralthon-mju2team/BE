package com.hq.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.expiresIn").value(3600));
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

    @Test
    void 로그인_직후_refresh하면_새_refresh_token을_발급한다() throws Exception {
        String refreshToken = signupAndLoginForRefresh();

        MvcResult result = refresh(refreshToken).andExpect(status().isOk()).andReturn();
        String rotatedRefreshToken = JsonPath.read(result.getResponse().getContentAsString(), "$.refreshToken");

        assertThat(rotatedRefreshToken).isNotEqualTo(refreshToken);
    }

    @Test
    void 같은_refresh_token을_동시에_요청하면_하나만_성공한다() throws Exception {
        String refreshToken = signupAndLoginForRefresh();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startBarrier = new CyclicBarrier(2);

        try {
            Future<Integer> first = executor.submit(() -> {
                startBarrier.await();
                return refresh(refreshToken).andReturn().getResponse().getStatus();
            });
            Future<Integer> second = executor.submit(() -> {
                startBarrier.await();
                return refresh(refreshToken).andReturn().getResponse().getStatus();
            });

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 401);
        } finally {
            executor.shutdownNow();
        }
    }

    private String signupAndLoginForRefresh() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String signupBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated());

        String loginBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        MvcResult loginResult = mockMvc.perform(post("/auth/email/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(loginResult.getResponse().getContentAsString(), "$.refreshToken");
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
        String body = """
                {"refreshToken":"%s"}
                """.formatted(refreshToken);
        return mockMvc.perform(post("/auth/refresh")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
