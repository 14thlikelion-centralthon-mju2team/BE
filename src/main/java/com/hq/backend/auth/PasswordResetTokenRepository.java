package com.hq.backend.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE PasswordResetToken t SET t.consumedAt = CURRENT_TIMESTAMP "
            + "WHERE t.userId = :userId AND t.type = :type "
            + "AND t.consumedAt IS NULL AND t.expiresAt > CURRENT_TIMESTAMP")
    void consumeAllActiveByUserIdAndType(UUID userId, String type);
}
