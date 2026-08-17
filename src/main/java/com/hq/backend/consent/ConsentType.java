package com.hq.backend.consent;

// user_consent.consent_type check 제약과 1:1 대응(거기는 소문자). Ensom ERD v3의
// consentType 값은 terms/privacy/location/marketing 4종뿐 — Vium 시절 CALENDAR·
// HEALTH_DATA는 새 스키마에 없다(ck_consent_type).
public enum ConsentType {
    TERMS,
    PRIVACY,
    LOCATION,
    MARKETING
}
