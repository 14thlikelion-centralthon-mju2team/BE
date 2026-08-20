package com.hq.backend.environment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

// 홈 날씨 카드(GET /environment/current). 테스트 환경은 기상청 키가 없어
// StubEnvironmentProvider가 응답하므로 값이 아니라 표기 규약을 고정한다.
@SpringBootTest
@AutoConfigureMockMvc
class EnvironmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 대표_장소가_있으면_한국어_표시값으로_현재_환경을_내려준다() throws Exception {
        String accessToken = signupAndLogin();
        registerPrimaryPlace(accessToken);

        mockMvc.perform(get("/environment/current").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temperature").value(22))
                .andExpect(jsonPath("$.sky").value("구름많음"))
                .andExpect(jsonPath("$.pm10Grade").value("보통"))
                .andExpect(jsonPath("$.pm25Grade").value("보통"))
                .andExpect(jsonPath("$.uvIndex").value(3));
    }

    @Test
    void 대표_장소가_없으면_404다() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(get("/environment/current").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRIMARY_PLACE_NOT_FOUND"));
    }

    @Test
    void 인증_없이_요청하면_401이다() throws Exception {
        mockMvc.perform(get("/environment/current"))
                .andExpect(status().isUnauthorized());
    }

    private void registerPrimaryPlace(String accessToken) throws Exception {
        mockMvc.perform(post("/places")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeType":"home","placeName":"집","address":"서울시 어딘가",
                                 "lat":37.4979,"lng":127.0276,"isPrimary":true}
                                """))
                .andExpect(status().isCreated());
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
}
