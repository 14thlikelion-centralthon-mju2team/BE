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

// ERD v3 USER_CONSENT — 테이블명이 user_consents(복수)에서 user_consent(단수)로 바뀌었고
// agreed(bool) 대신 action('agreed'|'revoked') 문자열, idempotency_key(UNIQUE, 필수)가
// 추가됐다. userId는 nullable — 탈퇴해도 동의 이력은 법정 보존 기간 유지(ON DELETE SET NULL).
@Entity
@Table(name = "user_consent")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID consentEventId;

    private UUID userId;

    // DB check 제약은 소문자('terms' 등), API는 대문자('TERMS')를 쓴다 — 변환은
    // ConsentService에서 처리하고 엔티티는 DB 값 그대로 문자열로 들고 있는다.
    @Column(nullable = false)
    private String consentType;

    @Column(nullable = false)
    private String policyVersion;

    @Column(nullable = false)
    private String action; // agreed | revoked

    @Column(nullable = false)
    private boolean isRequired;

    @Column(nullable = false)
    private UUID idempotencyKey;

    @Column(nullable = false)
    private Instant recordedAt;
}
