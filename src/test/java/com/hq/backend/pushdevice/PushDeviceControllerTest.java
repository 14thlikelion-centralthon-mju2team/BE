package com.hq.backend.pushdevice;

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
class PushDeviceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 기기를_등록하면_201과_등록내용을_반환한다() throws Exception {
        String accessToken = signupAndLogin();
        String installationId = UUID.randomUUID().toString();
        String body = """
                {"installationId":"%s","token":"fcm-token-abc","platform":"ANDROID"}
                """.formatted(installationId);

        mockMvc.perform(post("/push-devices")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.installationId").value(installationId))
                .andExpect(jsonPath("$.platform").value("android"))
                .andExpect(jsonPath("$.lastSeenAt").exists());
    }

    @Test
    void 같은_installation_id로_재등록하면_기존_행이_갱신된다() throws Exception {
        String accessToken = signupAndLogin();
        String installationId = UUID.randomUUID().toString();
        String firstBody = """
                {"installationId":"%s","token":"old-token","platform":"ANDROID"}
                """.formatted(installationId);
        String firstResponse = mockMvc.perform(post("/push-devices")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstBody))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String firstPushDeviceId = JsonPath.read(firstResponse, "$.push_device_id");

        String secondBody = """
                {"installationId":"%s","token":"new-token","platform":"IOS"}
                """.formatted(installationId);

        mockMvc.perform(post("/push-devices")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pushDeviceId").value(firstPushDeviceId))
                .andExpect(jsonPath("$.platform").value("ios"));
    }

    @Test
    void 존재하지_않는_platform이면_프로젝트_표준_에러_포맷으로_400() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"installationId":"%s","token":"fcm-token-abc","platform":"FOO"}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/push-devices")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void 인증_없이_요청하면_401() throws Exception {
        String body = """
                {"installationId":"%s","token":"fcm-token-abc","platform":"ANDROID"}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/push-devices").contentType(MediaType.APPLICATION_JSON).content(body))
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
