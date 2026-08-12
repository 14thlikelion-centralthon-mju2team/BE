package com.hq.backend.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> find(UUID userId, String idempotencyKey, String endpoint) {
        return idempotencyRecordRepository.findByUserIdAndIdempotencyKeyAndEndpoint(userId, idempotencyKey, endpoint);
    }

    @Transactional
    public void store(UUID userId, String idempotencyKey, String endpoint, int statusCode, String responseBody) {
        try {
            idempotencyRecordRepository.save(IdempotencyRecord.builder()
                    .userId(userId)
                    .idempotencyKey(idempotencyKey)
                    .endpoint(endpoint)
                    .statusCode(statusCode)
                    .responseBody(responseBody)
                    .createdAt(Instant.now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 키로 두 요청이 들어와 unique 제약 위반 — 이미 다른 쪽이 먼저 저장한
            // 것이므로 이 쪽은 그냥 무시한다(원래 응답은 이미 클라이언트에게 나간 뒤라 문제 없음).
        }
    }
}
