package com.hq.backend.auth.dto;

public record TokenResponse(String accessToken, String refreshToken, long expiresIn) {
}
