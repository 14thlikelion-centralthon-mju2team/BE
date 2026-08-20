package com.hq.backend.wellness;

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
class WellnessPrefControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 저장된_설정이_없으면_5개_토픽_기본값을_반환한다() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(get("/me/wellness-prefs").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[?(@.wellnessTopic=='uv')].isEnabled").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$[?(@.wellnessTopic=='uv')].dailyEventCap").value(org.hamcrest.Matchers.contains(1)));
    }

    @Test
    void 일부_토픽만_갱신해도_나머지는_기본값으로_유지된다() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(patch("/me/wellness-prefs")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prefs":[
                                  {"wellnessTopic":"UV","isEnabled":true,"remindIntervalMinutes":120,"dailyEventCap":1},
                                  {"wellnessTopic":"HYDRATION","isEnabled":false,"remindIntervalMinutes":null,"dailyEventCap":1}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.wellnessTopic=='uv')].isEnabled").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$[?(@.wellnessTopic=='uv')].remindIntervalMinutes").value(org.hamcrest.Matchers.contains(120)))
                .andExpect(jsonPath("$[?(@.wellnessTopic=='pm')].isEnabled").value(org.hamcrest.Matchers.contains(false)));

        mockMvc.perform(get("/me/wellness-prefs").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.wellnessTopic=='uv')].isEnabled").value(org.hamcrest.Matchers.contains(true)));
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
