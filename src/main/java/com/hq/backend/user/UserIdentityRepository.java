package com.hq.backend.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    Optional<UserIdentity> findByProviderAndProviderUid(String provider, String providerUid);

    List<UserIdentity> findAllByUserIdAndRevokedAtIsNull(UUID userId);
}
