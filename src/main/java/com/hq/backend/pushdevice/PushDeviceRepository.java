package com.hq.backend.pushdevice;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushDeviceRepository extends JpaRepository<PushDevice, UUID> {
    Optional<PushDevice> findByInstallationId(UUID installationId);

    List<PushDevice> findByUserIdAndTokenStatus(UUID userId, String tokenStatus);
}
