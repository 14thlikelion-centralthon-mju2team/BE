package com.hq.backend.event.classification;

import com.hq.backend.consent.UserConsent;
import com.hq.backend.consent.UserConsentRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AiClassificationGate {

    static final String PINNED_MODEL = "gpt-4o-mini-2024-07-18";
    private static final String PRIVACY_CONSENT_TYPE = "privacy";
    private static final String AGREED_ACTION = "agreed";

    private final UserConsentRepository consentRepository;
    private final AiClassificationProperties properties;

    public AiClassificationGate(UserConsentRepository consentRepository, AiClassificationProperties properties) {
        this.consentRepository = consentRepository;
        this.properties = properties;
    }

    public AiGateOutcome evaluate(UUID userId) {
        if (!isEnabledWithValidConfiguration()) {
            return AiGateOutcome.DISABLED;
        }
        if (userId == null) {
            return AiGateOutcome.SKIPPED_CONSENT;
        }
        if (rolloutBucket(userId) >= properties.classification().rolloutPercent()) {
            return AiGateOutcome.SKIPPED_ROLLOUT;
        }

        return consentRepository
                .findFirstByUserIdAndConsentTypeOrderByRecordedAtDescConsentEventIdDesc(userId, PRIVACY_CONSENT_TYPE)
                .filter(this::isExactAgreement)
                .isPresent() ? AiGateOutcome.ALLOWED : AiGateOutcome.SKIPPED_CONSENT;
    }

    static int rolloutBucket(UUID userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(userId.toString().getBytes(StandardCharsets.UTF_8));
            long firstEightBytes = 0;
            for (int index = 0; index < Long.BYTES; index++) {
                firstEightBytes = (firstEightBytes << Byte.SIZE) | Byte.toUnsignedLong(digest[index]);
            }
            return (int) Long.remainderUnsigned(firstEightBytes, 100);
        } catch (NoSuchAlgorithmException exception) {
            return 100;
        }
    }

    private boolean isEnabledWithValidConfiguration() {
        AiClassificationProperties.Classification classification = properties.classification();
        return classification != null
                && classification.enabled()
                && classification.rolloutPercent() >= 0
                && classification.rolloutPercent() <= 100
                && classification.maxPerSync() >= 0
                && classification.maxConcurrency() >= 1
                && properties.baseUrl() != null
                && hasText(properties.baseUrl().toString())
                && properties.connectTimeoutMs() >= 1
                && properties.readTimeoutMs() >= 1
                && hasText(properties.apiKey())
                && PINNED_MODEL.equals(properties.model())
                && hasText(classification.privacyPolicyVersion())
                && hasText(classification.classifierVersion())
                && hasText(classification.promptVersion())
                && hasText(classification.schemaVersion());
    }

    private boolean isExactAgreement(UserConsent consent) {
        return AGREED_ACTION.equals(consent.getAction())
                && properties.classification().privacyPolicyVersion().equals(consent.getPolicyVersion());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
