package com.hq.backend.consent;

import com.fasterxml.jackson.annotation.JsonValue;

// user_consent.consent_type check 제약과 1:1 대응(거기는 소문자). Ensom ERD v3의
// consentType 값은 terms/privacy/location/marketing 4종뿐 — Vium 시절 CALENDAR·
// HEALTH_DATA는 새 스키마에 없다(ck_consent_type).
public enum ConsentType {
    TERMS,
    PRIVACY,
    LOCATION,
    MARKETING;

    // wire 값은 API 명세·DB CHECK 제약과 같은 lower_snake다. name() 그대로 나가면
    // 대문자가 되어 클라이언트 파싱이 깨진다. 역직렬화는 Jackson이 이 값을 그대로 쓰고,
    // accept-case-insensitive-enums 설정이 대문자 요청도 계속 받아준다.
    @JsonValue
    public String wireValue() {
        return name().toLowerCase();
    }
}
