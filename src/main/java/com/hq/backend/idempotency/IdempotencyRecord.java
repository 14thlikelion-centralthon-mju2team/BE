package com.hq.backend.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 응답 스냅샷을 그대로 재생하기 위한 기록 — 클라이언트가 Idempotency-Key로 같은 요청을
// 재시도하면 실제 처리 없이 저장된 status_code/response_body를 그대로 돌려준다.
@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private String endpoint;

    @Column(nullable = false)
    private int statusCode;

    @Column(nullable = false)
    private String responseBody;

    @Column(nullable = false)
    private Instant createdAt;
}
