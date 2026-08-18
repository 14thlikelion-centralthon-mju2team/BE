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
                .andExpect(jsonPath("$.initial_prep_minutes").doesNotExist())
                .andExpect(jsonPath("$.arrival_buffer_minutes").value(10))
                .andExpect(jsonPath("$.notification_sensitivity").value("normal"))
                .andExpect(jsonPath("$.wellness_event_enabled").value(false))
                .andExpect(jsonPath("$.lockscreen_hide_sensitive").value(true));
    }

    @Test
    void 설정을_저장하면_반영되고_initialPrepMinutes는_null도_허용한다() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(patch("/me/settings")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"initial_prep_minutes":null,"arrival_buffer_minutes":15,
                                 "notification_sensitivity":"quiet","personalization_enabled":false,
                                 "auto_manage_enabled":true,"wellness_event_enabled":true,
                                 "lockscreen_hide_sensitive":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initial_prep_minutes").doesNotExist())
                .andExpect(jsonPath("$.arrival_buffer_minutes").value(15))
                .andExpect(jsonPath("$.notification_sensitivity").value("quiet"))
                .andExpect(jsonPath("$.personalization_enabled").value(false))
                .andExpect(jsonPath("$.wellness_event_enabled").value(true))
                .andExpect(jsonPath("$.lockscreen_hide_sensitive").value(false));

        mockMvc.perform(get("/me/settings").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrival_buffer_minutes").value(15));
    }

    @Test
    void 음수_준비시간은_400() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(patch("/me/settings")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"initial_prep_minutes":-5,"arrival_buffer_minutes":10,
                                 "notification_sensitivity":"normal","personalization_enabled":true,
                                 "auto_manage_enabled":true,"wellness_event_enabled":false,
                                 "lockscreen_hide_sensitive":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
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
