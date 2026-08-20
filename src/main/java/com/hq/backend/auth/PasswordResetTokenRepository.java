package com.hq.backend.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * 원자적 토큰 소비: 유효한(미소비·미만료) 토큰을 찾아 소비 처리한다.
     * 동시 요청이 와도 단 하나만 성공(affected=1)하고 나머지는 affected=0.
     */
    @Modifying
    @Query("UPDATE PasswordResetToken t SET t.consumedAt = CURRENT_TIMESTAMP "
            + "WHERE t.tokenHash = :tokenHash "
            + "AND t.consumedAt IS NULL AND t.expiresAt > CURRENT_TIMESTAMP")
    int consumeByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE PasswordResetToken t SET t.consumedAt = CURRENT_TIMESTAMP "
            + "WHERE t.userId = :userId AND t.type = :type "
            + "AND t.consumedAt IS NULL AND t.expiresAt > CURRENT_TIMESTAMP")
    void consumeAllActiveByUserIdAndType(UUID userId, String type);
}
