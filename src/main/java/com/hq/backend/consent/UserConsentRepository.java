package com.hq.backend.consent;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConsentRepository extends JpaRepository<UserConsent, UUID> {
}
