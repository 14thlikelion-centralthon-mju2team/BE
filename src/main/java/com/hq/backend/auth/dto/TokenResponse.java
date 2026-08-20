package com.hq.backend.auth.dto;

import java.util.List;

// API 명세 §2.2. consentRequired는 아직 동의가 필요한 필수 약관 목록이며, 빈 배열이
// 될 때까지 클라이언트가 홈 진입을 막는다(§2.9, PRD §11.2).
// 명세의 emailVerificationRequired는 담지 않는다 — 이메일 미인증 로그인은 여기까지
// 오지 못하고 403 EMAIL_VERIFICATION_REQUIRED로 끊기므로 항상 false다.
public record TokenResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UserInfo user,
        List<String> consentRequired
) {
    public record UserInfo(
            String userId,
            String nickname,
            String timezone,
            boolean isNew
    ) {}
}
