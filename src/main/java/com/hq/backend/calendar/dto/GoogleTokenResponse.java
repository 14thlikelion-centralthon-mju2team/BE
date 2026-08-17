package com.hq.backend.calendar.dto;

// POST https://oauth2.googleapis.com/token 응답 중 필요한 필드만 매핑.
// refreshToken은 이미 연결된 앱에 대한 재교환이면 구글이 안 줄 수 있다 — nullable.
// idToken은 openid 스코프가 있으면 같이 온다 — CalendarService가 이메일(계정 식별자)을
// 꺼내는 데만 쓰고 서명 검증은 하지 않는다(로그인 인증 자체가 아니라 계정 표시용).
public record GoogleTokenResponse(String accessToken, String refreshToken, String idToken, Long expiresIn) {
}
