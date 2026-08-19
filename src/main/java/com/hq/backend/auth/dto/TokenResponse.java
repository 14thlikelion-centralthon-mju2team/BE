package com.hq.backend.auth.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UserInfo user
) {
    public record UserInfo(
            String userId,
            String nickname,
            String timezone,
            boolean isNew
    ) {}
}
