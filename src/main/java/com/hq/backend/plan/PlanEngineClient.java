package com.hq.backend.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hq.backend.plan.dto.PlanEngineRequest;
import com.hq.backend.plan.dto.PlanEngineResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// 계획 계산 서비스(ai/plan-engine)가 일시적으로 안 떠 있어도 일정 생성 자체는 막지 않는다 —
// 호출 실패는 흡수하고 EventService가 plan:null로 응답하도록 empty를 반환한다(TR-11.5와
// 같은 원칙: 외부 의존성 부재가 핵심 흐름을 막지 않는다). 단, 흔적 없이 삼키면 장애를
// 못 알아채니 WARN으로는 남긴다.
//
// JSON은 여기서 직접 (역)직렬화한다 — 전역 Jackson은 SNAKE_CASE인데 엔진은 camelCase를
// 쓰기 때문이다. RestClient의 메시지 컨버터 목록을 갈아끼우는 방식도 시도했지만 근본
// 원인(PlanEngineClientConfig 참고 — 기본 JdkClientHttpRequestFactory가 이 서버로 보내는
// 요청 바디를 비워버림)과는 무관했고, 직접 (역)직렬화가 가장 단순하고 확실해 그대로 둔다.
@Service
@RequiredArgsConstructor
public class PlanEngineClient {

    private static final Logger log = LoggerFactory.getLogger(PlanEngineClient.class);

    private final RestClient planEngineRestClient;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public Optional<PlanEngineResponse> compute(PlanEngineRequest request) {
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            String responseJson = planEngineRestClient.post()
                    .uri("/internal/v1/plans/compute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .retrieve()
                    .body(String.class);
            return responseJson == null
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(responseJson, PlanEngineResponse.class));
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("계획 엔진 호출 실패 — plan:null로 진행합니다. cause={}", e.toString());
            return Optional.empty();
        }
    }
}
