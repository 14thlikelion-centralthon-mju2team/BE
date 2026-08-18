package com.hq.backend.pushdevice;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PushDeviceRepository extends JpaRepository<PushDevice, UUID> {
    Optional<PushDevice> findByInstallationId(UUID installationId);

    List<PushDevice> findByUserIdAndTokenStatus(UUID userId, String tokenStatus);

    @Modifying
    @Query("UPDATE PushDevice p SET p.tokenStatus = 'inactive', p.revokedAt = CURRENT_TIMESTAMP " +
           "WHERE p.userId = :userId AND p.tokenStatus = 'active'")
    int revokeAllByUserId(UUID userId);
}
