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
                .andExpect(jsonPath("$[?(@.wellness_topic=='UV')].is_enabled").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$[?(@.wellness_topic=='UV')].daily_event_cap").value(org.hamcrest.Matchers.contains(1)));
    }

    @Test
    void 일부_토픽만_갱신해도_나머지는_기본값으로_유지된다() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(patch("/me/wellness-prefs")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prefs":[
                                  {"wellness_topic":"UV","is_enabled":true,"remind_interval_minutes":120,"daily_event_cap":1},
                                  {"wellness_topic":"HYDRATION","is_enabled":false,"remind_interval_minutes":null,"daily_event_cap":1}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.wellness_topic=='UV')].is_enabled").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$[?(@.wellness_topic=='UV')].remind_interval_minutes").value(org.hamcrest.Matchers.contains(120)))
                .andExpect(jsonPath("$[?(@.wellness_topic=='PM')].is_enabled").value(org.hamcrest.Matchers.contains(false)));

        mockMvc.perform(get("/me/wellness-prefs").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.wellness_topic=='UV')].is_enabled").value(org.hamcrest.Matchers.contains(true)));
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
