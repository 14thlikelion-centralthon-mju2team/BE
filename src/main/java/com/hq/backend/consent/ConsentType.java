package com.hq.backend.consent;

// V1__init.sql user_consents.consent_type check 제약과 1:1 대응 (거기는 소문자).
// API는 대문자 상수명 그대로 주고받고, DB 왕복 변환은 ConsentService에서 처리한다.
public enum ConsentType {
    TERMS,
    PRIVACY,
    LOCATION,
    CALENDAR,
    HEALTH_DATA,
    MARKETING
}
