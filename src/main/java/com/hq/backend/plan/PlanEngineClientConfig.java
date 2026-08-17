package com.hq.backend.plan;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// 타임아웃은 ai/plan-engine/README.md 권장값(connect 1s, read 2s) 그대로. JSON 변환은
// PlanEngineClient가 직접 한다(camelCase 전용 ObjectMapper) — RestClient의 메시지 컨버터
// 목록을 건드리지 않는다.
//
// SimpleClientHttpRequestFactory(HttpURLConnection 기반)를 명시한다 — Boot가 기본
// 선택하는 JdkClientHttpRequestFactory(java.net.http.HttpClient 기반)로는 이
// uvicorn 서버에 요청 바디가 비어서 도착했다(원인 불명, 재현 확인함). 실제 계획 엔진을
// 로컬에서 띄우고 전체 흐름을 직접 검증해서 찾은 문제라 이 선택은 추측이 아니다.
@Configuration
public class PlanEngineClientConfig {

    @Bean
    public RestClient planEngineRestClient(@Value("${plan-engine.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(1000);
        requestFactory.setReadTimeout(2000);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
