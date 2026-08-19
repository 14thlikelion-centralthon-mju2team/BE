package com.hq.backend.personalization;

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

// TRD §6 원인 분리 EMA 보정의 저장소. scopeType은 MODEL-02를 이미 수용하지만 MVP는 'global'만
// 쓴다. valid_from/valid_to로 이력을 남겨 과거 계획의 재현성을 유지한다(설명가능성, PRD §16.9).
@Entity
@Table(name = "user_prep_estimate")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPrepEstimate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID estimateId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String scopeType; // global | event_kind | weather | origin_place | time_band

    private String scopeValue;

    @Setter
    @Column(nullable = false)
    private int estimatedMinutes;

    @Setter
    @Column(nullable = false)
    private int sampleCount;

    @Setter
    @Column(nullable = false)
    private BigDecimal confidence;

    @Column(nullable = false)
    private String modelVersion;

    @Setter
    private String adjustmentReason; // PRD §8.5 "왜 보정됐는지" 문장

    @Column(nullable = false)
    private boolean coldStartAdjusted;

    @Column(nullable = false)
    private Instant validFrom;

    @Setter
    private Instant validTo;
}
