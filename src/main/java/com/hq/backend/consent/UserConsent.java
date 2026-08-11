package com.hq.backend.consent;

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

// V1__init.sql: user_consents는 append-only (trg_user_consents_append_only 트리거가
// UPDATE/DELETE를 막는다). 철회도 agreed=false인 새 행을 INSERT하는 것으로 표현하고
// 기존 행은 절대 건드리지 않는다 — 그래서 이 엔티티에도 update용 setter가 없다.
@Entity
@Table(name = "user_consents")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID userId;

    // DB check 제약은 소문자('health_data' 등), API는 대문자('HEALTH_DATA')를 쓴다 —
    // 변환은 ConsentService에서 처리하고 엔티티는 DB 값 그대로 문자열로 들고 있는다.
    @Column(nullable = false)
    private String consentType;

    @Column(nullable = false)
    private String policyVersion;

    @Column(nullable = false)
    private Boolean agreed;

    @Column(nullable = false)
    private Instant recordedAt;
}
