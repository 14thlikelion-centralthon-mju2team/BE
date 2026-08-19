package com.hq.backend.user.dto;

import com.hq.backend.auth.RefreshToken;
import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
        UUID refreshTokenId,
        Instant issuedAt,
        boolean isCurrent
) {

    public static SessionResponse from(RefreshToken token, boolean isCurrent) {
        return new SessionResponse(
                token.getId(),
                token.getIssuedAt(),
                isCurrent);
    }
}
