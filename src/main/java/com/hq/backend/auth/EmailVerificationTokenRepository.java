package com.hq.backend.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    List<EmailVerificationToken> findByUserIdAndUsedAtIsNullAndInvalidatedAtIsNull(UUID userId);

    Optional<EmailVerificationToken> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);
}
