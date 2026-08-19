package com.hq.backend.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findAllByUserIdAndRevokedAtIsNullAndExpiresAtAfter(UUID userId, Instant now);

    /**
     * 토큰을 원자적으로 소비한다. revokedAt이 NULL이고 만료 전인 경우에만 revoke 처리.
     * 반환값이 0이면 이미 소비됨 (동시 요청 방어).
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = CURRENT_TIMESTAMP " +
           "WHERE r.tokenHash = :tokenHash AND r.revokedAt IS NULL AND r.expiresAt > CURRENT_TIMESTAMP")
    int revokeByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = CURRENT_TIMESTAMP WHERE r.userId = :userId AND r.revokedAt IS NULL")
    int revokeAllByUserId(UUID userId);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = CURRENT_TIMESTAMP " +
           "WHERE r.userId = :userId AND r.revokedAt IS NULL AND r.tokenHash <> :excludeTokenHash")
    int revokeAllByUserIdExcept(UUID userId, String excludeTokenHash);
}
