package com.hq.backend.metrics;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// TRD §16.1 — PRD §24 성공 지표는 이 이벤트 스키마 없이는 측정되지 않는다(P0 필수).
// ERD 문서에는 없는 테이블이다 — 마일스톤이 M4(feat/be-lifecycle-feedback) 범위로
// "지표 이벤트"를 명시하고 있어 TRD §16 요구사항에 맞춰 신설했다.
// append-only. 좌표·캘린더 제목 원문·민감 준비 항목명은 payload에 절대 넣지 않는다
// (절대 원칙 8, TR-10 집계 경계).
@Entity
@Table(name = "product_event")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID productEventId;

    private UUID userId;

    @Column(nullable = false)
    private String eventName;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant receivedAt;

    private UUID clientEventId;

    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;
}
