package com.hq.backend.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// TRD §16.1 — PRD §24 성공 지표는 이 적재 없이는 측정되지 않는다. append-only, 좌표·캘린더
// 제목 원문·민감 준비 항목명은 payload에 절대 넣지 않는다(절대 원칙 8, TR-10 집계 경계).
// 지표 적재 실패가 본 기능(계획 생성, 행동 기록 등)을 막으면 안 된다 — 실패는 흡수한다.
@Service
@RequiredArgsConstructor
public class ProductEventService {

    private static final Logger log = LoggerFactory.getLogger(ProductEventService.class);

    private final ProductEventRepository productEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void record(UUID userId, String eventName, Map<String, Object> payload) {
        try {
            Instant now = Instant.now();
            productEventRepository.save(ProductEvent.builder()
                    .userId(userId)
                    .eventName(eventName)
                    .occurredAt(now)
                    .receivedAt(now)
                    .payload(objectMapper.writeValueAsString(payload))
                    .build());
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("[ProductEvent] 적재 실패 — eventName={}, cause={}", eventName, e.toString());
        }
    }
}
