package com.hq.backend.place;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class PlaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserPlaceRepository userPlaceRepository;

    @Test
    void 장소를_생성하면_201과_평문_좌표를_반환하고_DB엔_암호화돼_저장된다() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"place_type":"HOME","place_name":"집","address":"서울시 어딘가",
                 "lat":37.5,"lng":127.0,"is_primary":true}
                """;

        String response = mockMvc.perform(post("/places")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.place_type").value("HOME"))
                .andExpect(jsonPath("$.lat").value(37.5))
                .andExpect(jsonPath("$.lng").value(127.0))
                .andExpect(jsonPath("$.is_primary").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String placeId = JsonPath.read(response, "$.place_id");
        UserPlace saved = userPlaceRepository.findById(UUID.fromString(placeId)).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(new String(saved.getLatEnc())).doesNotContain("37.5");
    }

    @Test
    void 대표_장소는_한_번에_하나만_유지된다() throws Exception {
        String accessToken = signupAndLogin();
        String first = mockMvc.perform(post("/places")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"place_type":"HOME","place_name":"집","address":"주소1",
                                 "lat":37.5,"lng":127.0,"is_primary":true}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstId = JsonPath.read(first, "$.place_id");

        mockMvc.perform(post("/places")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"place_type":"WORK","place_name":"회사","address":"주소2",
                                 "lat":37.6,"lng":127.1,"is_primary":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.is_primary").value(true));

        mockMvc.perform(get("/places").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.place_id=='" + firstId + "')].is_primary").value(org.hamcrest.Matchers.contains(false)));
    }

    @Test
    void 잘못된_위도로_생성하면_422() throws Exception {
        String accessToken = signupAndLogin();
        mockMvc.perform(post("/places")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"place_type":"HOME","place_name":"집","address":"서울시 어딘가",
                                 "lat":200,"lng":127.0,"is_primary":false}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void 수정과_삭제가_소유자_기준으로_동작한다() throws Exception {
        String accessToken = signupAndLogin();
        String created = mockMvc.perform(post("/places")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"place_type":"HOME","place_name":"집","address":"서울시 어딘가",
                                 "lat":37.5,"lng":127.0,"is_primary":false}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String placeId = JsonPath.read(created, "$.place_id");

        mockMvc.perform(patch("/places/" + placeId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"place_name":"우리집"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.place_name").value("우리집"))
                .andExpect(jsonPath("$.lat").value(37.5));

        mockMvc.perform(delete("/places/" + placeId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/places").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 다른_사용자의_장소는_404() throws Exception {
        String ownerToken = signupAndLogin();
        String created = mockMvc.perform(post("/places")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"place_type":"HOME","place_name":"집","address":"서울시 어딘가",
                                 "lat":37.5,"lng":127.0,"is_primary":false}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String placeId = JsonPath.read(created, "$.place_id");

        String otherToken = signupAndLogin();
        mockMvc.perform(delete("/places/" + placeId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLACE_NOT_FOUND"));
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
