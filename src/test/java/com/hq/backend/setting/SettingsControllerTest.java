package com.hq.backend.setting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class SettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 저장된_설정이_없으면_기본값을_반환한다() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(get("/me/settings").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialPrepMinutes").doesNotExist())
                .andExpect(jsonPath("$.arrivalBufferMinutes").value(10))
                .andExpect(jsonPath("$.notificationSensitivity").value("normal"))
                .andExpect(jsonPath("$.wellnessEventEnabled").value(false))
                .andExpect(jsonPath("$.lockscreenHideSensitive").value(true));
    }

    @Test
    void 설정을_저장하면_반영되고_initialPrepMinutes는_null도_허용한다() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(patch("/me/settings")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"initialPrepMinutes":null,"arrivalBufferMinutes":15,
                                 "notificationSensitivity":"quiet","personalizationEnabled":false,
                                 "autoManageEnabled":true,"wellnessEventEnabled":true,
                                 "lockscreenHideSensitive":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialPrepMinutes").doesNotExist())
                .andExpect(jsonPath("$.arrivalBufferMinutes").value(15))
                .andExpect(jsonPath("$.notificationSensitivity").value("quiet"))
                .andExpect(jsonPath("$.personalizationEnabled").value(false))
                .andExpect(jsonPath("$.wellnessEventEnabled").value(true))
                .andExpect(jsonPath("$.lockscreenHideSensitive").value(false));

        mockMvc.perform(get("/me/settings").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrivalBufferMinutes").value(15));
    }

    @Test
    void 음수_준비시간은_400() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(patch("/me/settings")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"initialPrepMinutes":-5,"arrivalBufferMinutes":10,
                                 "notificationSensitivity":"normal","personalizationEnabled":true,
                                 "autoManageEnabled":true,"wellnessEventEnabled":false,
                                 "lockscreenHideSensitive":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
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

        return JsonPath.read(response, "$.accessToken");
    }
}
