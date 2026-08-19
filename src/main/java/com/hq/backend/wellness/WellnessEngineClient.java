package com.hq.backend.wellness;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hq.backend.wellness.dto.DailySummaryEngineRequest;
import com.hq.backend.wellness.dto.DailySummaryEngineResponse;
import com.hq.backend.wellness.dto.RushLoadEngineRequest;
import com.hq.backend.wellness.dto.RushLoadEngineResponse;
import com.hq.backend.wellness.dto.WellnessEngineRequest;
import com.hq.backend.wellness.dto.WellnessEngineResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// PlanEngineClient와 같은 이유로 실패를 흡수한다(TR-11.5) — 웰니스 실패는 시간 계획에
// 영향을 주지 않고 웰니스만 조용히 생략된다. RestClient 빈은 PlanEngineClientConfig 걸
// 그대로 쓴다(세 엔진이 같은 FastAPI 앱).
@Service
@RequiredArgsConstructor
public class WellnessEngineClient {

    private static final Logger log = LoggerFactory.getLogger(WellnessEngineClient.class);

    private final RestClient planEngineRestClient;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public Optional<WellnessEngineResponse> evaluate(WellnessEngineRequest request) {
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            String responseJson = planEngineRestClient.post()
                    .uri("/internal/v1/wellness/evaluate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .retrieve()
                    .body(String.class);
            return responseJson == null
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(responseJson, WellnessEngineResponse.class));
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("웰니스 엔진 호출 실패 — 웰니스 없이 계획을 진행합니다. cause={}", e.toString());
            return Optional.empty();
        }
    }

    public Optional<RushLoadEngineResponse> computeRushLoad(RushLoadEngineRequest request) {
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            String responseJson = planEngineRestClient.post()
                    .uri("/internal/v1/wellness/rush-load")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .retrieve()
                    .body(String.class);
            return responseJson == null
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(responseJson, RushLoadEngineResponse.class));
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("웰니스 RLS 엔진 호출 실패 — RLS 없이 실행 결과를 유지합니다. cause={}", e.toString());
            return Optional.empty();
        }
    }

    public Optional<DailySummaryEngineResponse> summarizeDay(DailySummaryEngineRequest request) {
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            String responseJson = planEngineRestClient.post()
                    .uri("/internal/v1/wellness/daily-summary")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .retrieve()
                    .body(String.class);
            return responseJson == null
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(responseJson, DailySummaryEngineResponse.class));
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("웰니스 DWL 엔진 호출 실패 — 기존 요약 계산을 사용합니다. cause={}", e.toString());
            return Optional.empty();
        }
    }

}