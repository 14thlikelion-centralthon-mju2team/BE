package com.hq.backend.common.config;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Boot 4.1의 현재 webmvc 조합은 ObjectMapper를 자동 등록하지 않는다. API JSON 규약과
// 서비스 간 일관성을 위해 Spring Bean으로 한 번만 설정한다.
// FE와 합의: 응답은 camelCase (Jackson 기본값).
@Configuration
public class JacksonConfig {

    // 요청 바디의 enum 대소문자 무시(application.yaml의 spring.jackson.mapper.accept-case-insensitive-enums
    // 와 동일한 설정)는 이 Bean이 아니라 그 프로퍼티가 실제로 적용된다 — HTTP 요청/응답에 쓰이는
    // MappingJackson2HttpMessageConverter는 이 objectMapper() Bean과 별개로 구성된다. 이 Bean은
    // ObjectMapper를 직접 주입받는 서비스(예: DailySummaryService)에서만 쓰인다.
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);
    }
}
