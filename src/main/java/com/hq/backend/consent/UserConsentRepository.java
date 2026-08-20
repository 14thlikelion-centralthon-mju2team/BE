package com.hq.backend.consent;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConsentRepository extends JpaRepository<UserConsent, UUID> {

    Optional<UserConsent> findFirstByUserIdAndConsentTypeOrderByRecordedAtDescConsentEventIdDesc(
            UUID userId, String consentType);
}
