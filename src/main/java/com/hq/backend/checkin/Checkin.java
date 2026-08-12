package com.hq.backend.checkin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// V1__init.sql daily_checkins: unique(user_id, log_date) — 같은 날 중복 입력은 서비스에서 사전 검증한다.
// consent_type과 마찬가지로 DB check 제약은 소문자, API는 대문자 enum을 쓰고 변환은 서비스가 담당한다.
@Entity
@Table(name = "daily_checkins")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Checkin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private LocalDate logDate;

    private Integer availableMinutes;

    private String conditionInferred;

    private String conditionFinal;

    private Boolean conditionAccepted;

    private String focusArea;

    @Column(nullable = false)
    private Boolean isRestDay;

    @Column(nullable = false)
    private Instant createdAt;
}
