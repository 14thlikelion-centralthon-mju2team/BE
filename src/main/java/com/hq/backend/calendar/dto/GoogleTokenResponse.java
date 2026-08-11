package com.hq.backend.calendar.dto;

// POST https://oauth2.googleapis.com/token 응답 중 필요한 필드만 매핑.
// refreshToken은 이미 연결된 앱에 대한 재교환이면 구글이 안 줄 수 있다 — nullable.
public record GoogleTokenResponse(String accessToken, String refreshToken, String scope, Long expiresIn) {
}
