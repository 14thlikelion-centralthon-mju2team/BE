package com.hq.backend.auth;

import com.hq.backend.common.exception.ApiException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiration-ms}") long accessTokenExpirationMs,
            @Value("${app.jwt.refresh-token-expiration-ms}") long refreshTokenExpirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
    }

    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    public String generateAccessToken(UUID userId) {
        return buildToken(userId, accessTokenExpirationMs, TYPE_ACCESS, false);
    }

    public String generateRefreshToken(UUID userId) {
        // NumericDate는 초 단위라 같은 초에 발급하면 sub/typ/iat/exp가 모두 같아질 수 있다.
        // refresh_token.token_hash UNIQUE와 회전 안전성을 위해 매 발급마다 랜덤 jti를 포함한다.
        return buildToken(userId, refreshTokenExpirationMs, TYPE_REFRESH, true);
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationMs / 1000;
    }

    // feat/actions-routines에서 추가: 지금까지는 토큰 발급만 했지 검증하는 코드가 없어서
    // "이 요청을 누가 보냈는지" 알아낼 방법이 없었다. routines가 user_id 소유권 검증을
    // 요구해서(§7 공통 계약) 최소한의 파싱만 추가한다. 역할/권한 체계는 setting/security
    // (Phase 6, 백A 담당) 범위라 여기서는 손대지 않는다.
    // #15에서 추가: typ 클레임을 확인해서 refresh token이 일반 API 인증(access token 전용)을
    // 우회하지 못하게 막는다.
    public UUID getUserId(String token) {
        try {
            var claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 토큰입니다.");
            }
            return UUID.fromString(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 토큰입니다.");
        }
    }

    /**
     * Refresh 토큰을 검증하고 userId를 반환한다.
     * typ 클레임이 "refresh"가 아니거나 서명/만료 검증에 실패하면 예외를 던진다.
     */
    public UUID getUserIdFromRefreshToken(String token) {
        try {
            var claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 토큰입니다.");
            }
            return UUID.fromString(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 토큰입니다.");
        }
    }

    public long getRefreshTokenExpirationMs() {
        return refreshTokenExpirationMs;
    }

    private String buildToken(UUID userId, long expirationMs, String type, boolean includeJti) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)));
        if (includeJti) {
            builder.id(UUID.randomUUID().toString());
        }
        return builder
                .signWith(key)
                .compact();
    }
}
