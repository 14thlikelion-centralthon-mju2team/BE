package com.hq.backend.user;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    Optional<UserIdentity> findByProviderAndProviderUid(String provider, String providerUid);

    Optional<UserIdentity> findByProviderAndProviderUidAndRevokedAtIsNull(String provider, String providerUid);

    Optional<UserIdentity> findByUserIdAndProviderAndRevokedAtIsNull(UUID userId, String provider);

    List<UserIdentity> findAllByUserIdAndRevokedAtIsNull(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserIdentity u WHERE u.userId = :userId AND u.revokedAt IS NULL")
    List<UserIdentity> findAllActiveByUserIdForUpdate(@Param("userId") UUID userId);
}
