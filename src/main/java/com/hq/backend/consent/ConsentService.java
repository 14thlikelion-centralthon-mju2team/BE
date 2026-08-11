package com.hq.backend.consent;

import com.hq.backend.consent.dto.ConsentRequest;
import com.hq.backend.consent.dto.ConsentResponse;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConsentService {

    private final UserConsentRepository userConsentRepository;

    @Transactional
    public ConsentResponse record(UUID userId, ConsentRequest request) {
        UserConsent saved = userConsentRepository.save(UserConsent.builder()
                .userId(userId)
                .consentType(request.consentType().name().toLowerCase())
                .policyVersion(request.policyVersion())
                .agreed(request.agreed())
                .recordedAt(Instant.now())
                .build());

        return new ConsentResponse(
                saved.getId(),
                ConsentType.valueOf(saved.getConsentType().toUpperCase()),
                saved.getAgreed(),
                saved.getRecordedAt());
    }
}
