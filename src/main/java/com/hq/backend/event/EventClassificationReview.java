package com.hq.backend.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// TRD §4.6. titleSnapshot은 분류 입력으로만 잠깐 쓰이고 답변 즉시(또는 24시간 후 배치로)
// NULL 처리된다 — ck_title_purged가 DB에서 이 규약을 강제한다.
@Entity
@Table(name = "event_classification_review")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventClassificationReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID reviewId;

    @Column(nullable = false)
    private UUID eventId;

    @Setter
    private String titleSnapshot;

    @Column(nullable = false)
    private String questionType;

    private String suggestedValue;

    @Setter
    private String userAnswer;

    private String modelVersion;

    private BigDecimal classificationConfidence;

    @Column(nullable = false)
    private Instant askedAt;

    @Setter
    private Instant answeredAt;

    @Setter
    private Instant titlePurgedAt;
}
