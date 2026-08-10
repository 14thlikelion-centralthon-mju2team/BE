package com.hq.backend.auth.dto;

// GET https://oauth2.googleapis.com/tokeninfo?id_token=... 응답 중 필요한 필드만 매핑
public record GoogleUserInfoResponse(
        String sub,   // 구글 유저 고유 ID
        String email,
        String aud    // 이 토큰이 발급된 앱의 클라이언트 ID — 우리 앱용 토큰인지 검증에 사용
) {
}
