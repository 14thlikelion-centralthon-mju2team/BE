package com.hq.backend.preprule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hq.backend.metrics.ProductEventRepository;
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
class PrepRuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductEventRepository productEventRepository;

    @Test
    void 준비_규칙을_생성하면_201과_저장된_내용을_반환한다() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"ruleName":"영양제","ruleCategory":"SUPPLEMENT","actionType":"CONSUME",
                 "ruleTiming":"PRE_DEPARTURE","isRequired":false,"isSensitive":false,"fromChip":true}
                """;

        mockMvc.perform(post("/prep-items")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleName").value("영양제"))
                .andExpect(jsonPath("$.ruleCategory").value("SUPPLEMENT"))
                .andExpect(jsonPath("$.fromChip").value(true));

        boolean logged = productEventRepository.findAll().stream()
                .anyMatch(e -> "prep_rule_created".equals(e.getEventName()));
        assertThat(logged).isTrue();
    }

    @Test
    void timed_routine인데_defaultMinutes가_없으면_422() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"ruleName":"스트레칭","ruleCategory":"ROUTINE","actionType":"TIMED_ROUTINE",
                 "ruleTiming":"PRE_DEPARTURE","isRequired":false,"isSensitive":false,"fromChip":false}
                """;

        mockMvc.perform(post("/prep-items")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void 민감_항목을_추천칩으로_등록하면_422() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"ruleName":"수면제","ruleCategory":"MEDICATION","actionType":"CONSUME",
                 "ruleTiming":"PRE_DEPARTURE","isRequired":false,"isSensitive":true,"fromChip":true}
                """;

        mockMvc.perform(post("/prep-items")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SENSITIVE_CHIP_REJECTED"));
    }

    @Test
    void medication_카테고리는_isSensitive가_false여도_서버가_true로_강제한다() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"ruleName":"영양제 아님 약","ruleCategory":"MEDICATION","actionType":"CONSUME",
                 "ruleTiming":"PRE_DEPARTURE","isRequired":false,"isSensitive":false,"fromChip":false}
                """;

        mockMvc.perform(post("/prep-items")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isSensitive").value(true));
    }

    @Test
    void 목록_조회는_생성한_규칙을_반환하고_삭제하면_더이상_보이지_않는다() throws Exception {
        String accessToken = signupAndLogin();
        String createBody = """
                {"ruleName":"우산","ruleCategory":"GENERAL_ITEM","actionType":"CARRY",
                 "ruleTiming":"PRE_DEPARTURE","isRequired":true,"isSensitive":false,"fromChip":false}
                """;
        String createResponse = mockMvc.perform(post("/prep-items")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String prepRuleId = JsonPath.read(createResponse, "$.prep_rule_id");

        mockMvc.perform(get("/prep-items").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].prep_rule_id").value(prepRuleId));

        mockMvc.perform(patch("/prep-items/" + prepRuleId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ruleName":"장우산"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleName").value("장우산"));

        mockMvc.perform(delete("/prep-items/" + prepRuleId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/prep-items").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void medication_항목의_isSensitive를_PATCH로_끌_수_없다() throws Exception {
        String accessToken = signupAndLogin();
        String createBody = """
                {"ruleName":"수면제","ruleCategory":"MEDICATION","actionType":"CONSUME",
                 "ruleTiming":"PRE_DEPARTURE","isRequired":false,"isSensitive":false,"fromChip":false}
                """;
        String createResponse = mockMvc.perform(post("/prep-items")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isSensitive").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String prepRuleId = JsonPath.read(createResponse, "$.prep_rule_id");

        mockMvc.perform(patch("/prep-items/" + prepRuleId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"isSensitive":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSensitive").value(true));
    }

    @Test
    void fromChip_항목을_PATCH로_민감하게_바꾸면_422() throws Exception {
        String accessToken = signupAndLogin();
        String createBody = """
                {"ruleName":"선크림","ruleCategory":"GENERAL_ITEM","actionType":"CARRY",
                 "ruleTiming":"PRE_DEPARTURE","isRequired":false,"isSensitive":false,"fromChip":true}
                """;
        String createResponse = mockMvc.perform(post("/prep-items")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String prepRuleId = JsonPath.read(createResponse, "$.prep_rule_id");

        mockMvc.perform(patch("/prep-items/" + prepRuleId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"isSensitive":true}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SENSITIVE_CHIP_REJECTED"));
    }

    @Test
    void 다른_사용자의_준비_규칙을_수정하거나_삭제하면_404() throws Exception {
        String ownerToken = signupAndLogin();
        String createBody = """
                {"ruleName":"우산","ruleCategory":"GENERAL_ITEM","actionType":"CARRY",
                 "ruleTiming":"PRE_DEPARTURE","isRequired":true,"isSensitive":false,"fromChip":false}
                """;
        String createResponse = mockMvc.perform(post("/prep-items")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String prepRuleId = JsonPath.read(createResponse, "$.prep_rule_id");
        String otherToken = signupAndLogin();

        mockMvc.perform(patch("/prep-items/" + prepRuleId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ruleName":"장우산"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PREP_RULE_NOT_FOUND"));

        mockMvc.perform(delete("/prep-items/" + prepRuleId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PREP_RULE_NOT_FOUND"));
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
