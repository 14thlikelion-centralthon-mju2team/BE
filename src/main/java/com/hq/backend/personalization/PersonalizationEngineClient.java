package com.hq.backend.personalization;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hq.backend.personalization.dto.PersonalizationEngineRequest;
import com.hq.backend.personalization.dto.PersonalizationEngineResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// PlanEngineClient와 같은 이유로 실패를 흡수한다(TR-11.5) — 보정 실패는 행동 기록 자체를
// 막지 않고 이번 표본만 학습에서 조용히 빠진다. ai/plan-engine이 Plan·Personalization·
// Wellness를 한 FastAPI 앱으로 서빙하므로 RestClient 빈은 PlanEngineClientConfig 걸 그대로 쓴다.
@Service
@RequiredArgsConstructor
public class PersonalizationEngineClient {

    private static final Logger log = LoggerFactory.getLogger(PersonalizationEngineClient.class);

    private final RestClient planEngineRestClient;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public Optional<PersonalizationEngineResponse> adjust(PersonalizationEngineRequest request) {
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            String responseJson = planEngineRestClient.post()
                    .uri("/internal/v1/personalization/adjust")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .retrieve()
                    .body(String.class);
            return responseJson == null
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(responseJson, PersonalizationEngineResponse.class));
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("개인화 엔진 호출 실패 — 이번 표본은 보정 없이 넘어갑니다. cause={}", e.toString());
            return Optional.empty();
        }
    }
}
