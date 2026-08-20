package com.hq.backend.event.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hq.backend.consent.UserConsent;
import com.hq.backend.consent.UserConsentRepository;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiClassificationGateTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private UserConsentRepository consentRepository;

    @Test
    void disabled_configuration_fails_closed_without_reading_consent() {
        AiClassificationGate gate = new AiClassificationGate(
                consentRepository, properties(false, "key", "privacy-ai-v1", 100, "gpt-4o-mini-2024-07-18"), metrics());

        assertThat(gate.evaluate(USER_ID)).isEqualTo(AiGateOutcome.DISABLED);
        verifyNoInteractions(consentRepository);
    }

    @Test
    void blank_api_key_or_blank_required_version_fails_closed() {
        assertThat(gate(properties(true, " ", "privacy-ai-v1", 100, "gpt-4o-mini-2024-07-18"))
                .evaluate(USER_ID)).isEqualTo(AiGateOutcome.DISABLED);
        assertThat(gate(properties(true, "key", "", 100, "gpt-4o-mini-2024-07-18"))
                .evaluate(USER_ID)).isEqualTo(AiGateOutcome.DISABLED);
        assertThat(gate(properties(true, "key", "privacy-ai-v1", 100, "gpt-4o-mini"))
                .evaluate(USER_ID)).isEqualTo(AiGateOutcome.DISABLED);
    }

    @Test
    void blank_base_url_or_provenance_version_fails_closed() {
        AiClassificationProperties blankBaseUrl = new AiClassificationProperties(
                URI.create(""), "key", "gpt-4o-mini-2024-07-18", 3_000, 10_000,
                new AiClassificationProperties.Classification(
                        true, 100, 5, 2, "privacy-ai-v1", "", "event-online-ko-v1", "event-online-v1"));

        assertThat(gate(blankBaseUrl).evaluate(USER_ID)).isEqualTo(AiGateOutcome.DISABLED);
    }

    @Test
    void non_https_or_unapproved_openai_base_url_fails_closed_before_consent_lookup() {
        AiClassificationProperties httpEndpoint = propertiesWithBaseUrl("http://api.openai.com/v1");
        AiClassificationProperties arbitraryEndpoint = propertiesWithBaseUrl("https://collector.example/v1");

        assertThat(gate(httpEndpoint).evaluate(USER_ID)).isEqualTo(AiGateOutcome.DISABLED);
        assertThat(gate(arbitraryEndpoint).evaluate(USER_ID)).isEqualTo(AiGateOutcome.DISABLED);
        verifyNoInteractions(consentRepository);
    }

    @Test
    void rollout_zero_and_one_hundred_are_stable_boundaries() {
        when(consentRepository.findFirstByUserIdAndConsentTypeOrderByRecordedAtDescConsentEventIdDesc(USER_ID, "privacy"))
                .thenReturn(Optional.of(consent("agreed", "privacy-ai-v1")));

        assertThat(gate(properties(true, "key", "privacy-ai-v1", 0, "gpt-4o-mini-2024-07-18"))
                .evaluate(USER_ID)).isEqualTo(AiGateOutcome.SKIPPED_ROLLOUT);
        assertThat(gate(properties(true, "key", "privacy-ai-v1", 100, "gpt-4o-mini-2024-07-18"))
                .evaluate(USER_ID)).isEqualTo(AiGateOutcome.ALLOWED);
    }

    @Test
    void rollout_bucket_is_stable_sha_256_uuid_bucket() {
        assertThat(AiClassificationGate.rolloutBucket(USER_ID)).isEqualTo(33);
    }

    @Test
    void latest_revoke_or_non_exact_action_or_policy_skips_consent() {
        when(consentRepository.findFirstByUserIdAndConsentTypeOrderByRecordedAtDescConsentEventIdDesc(USER_ID, "privacy"))
                .thenReturn(Optional.of(consent("revoked", "privacy-ai-v1")))
                .thenReturn(Optional.of(consent("AGREED", "privacy-ai-v1")))
                .thenReturn(Optional.of(consent("agreed", "Privacy-ai-v1")))
                .thenReturn(Optional.of(consent("agreed", "privacy-ai-v1 ")))
                .thenReturn(Optional.of(consent("agreed", "privacy-ai-v1.0")));
        AiClassificationGate gate = gate(properties(true, "key", "privacy-ai-v1", 100, "gpt-4o-mini-2024-07-18"));

        assertThat(gate.evaluate(USER_ID)).isEqualTo(AiGateOutcome.SKIPPED_CONSENT);
        assertThat(gate.evaluate(USER_ID)).isEqualTo(AiGateOutcome.SKIPPED_CONSENT);
        assertThat(gate.evaluate(USER_ID)).isEqualTo(AiGateOutcome.SKIPPED_CONSENT);
        assertThat(gate.evaluate(USER_ID)).isEqualTo(AiGateOutcome.SKIPPED_CONSENT);
        assertThat(gate.evaluate(USER_ID)).isEqualTo(AiGateOutcome.SKIPPED_CONSENT);
        verify(consentRepository, org.mockito.Mockito.times(5))
                .findFirstByUserIdAndConsentTypeOrderByRecordedAtDescConsentEventIdDesc(USER_ID, "privacy");
    }

    @Test
    void reevaluates_latest_consent_for_every_call() {
        when(consentRepository.findFirstByUserIdAndConsentTypeOrderByRecordedAtDescConsentEventIdDesc(USER_ID, "privacy"))
                .thenReturn(Optional.of(consent("agreed", "privacy-ai-v1")))
                .thenReturn(Optional.of(consent("revoked", "privacy-ai-v1")));
        AiClassificationGate gate = gate(properties(true, "key", "privacy-ai-v1", 100, "gpt-4o-mini-2024-07-18"));

        assertThat(gate.evaluate(USER_ID)).isEqualTo(AiGateOutcome.ALLOWED);
        assertThat(gate.evaluate(USER_ID)).isEqualTo(AiGateOutcome.SKIPPED_CONSENT);
        verify(consentRepository, org.mockito.Mockito.times(2))
                .findFirstByUserIdAndConsentTypeOrderByRecordedAtDescConsentEventIdDesc(USER_ID, "privacy");
    }

    private AiClassificationGate gate(AiClassificationProperties properties) {
        return new AiClassificationGate(consentRepository, properties, metrics());
    }

    private AiClassificationMetrics metrics() {
        return new AiClassificationMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private AiClassificationProperties properties(
            boolean enabled, String apiKey, String privacyPolicyVersion, int rolloutPercent, String model) {
        return new AiClassificationProperties(
                URI.create("https://api.openai.com/v1"), apiKey, model, 3_000, 10_000,
                new AiClassificationProperties.Classification(
                        enabled, rolloutPercent, 5, 2, privacyPolicyVersion,
                        "event-online-review-v1", "event-online-ko-v1", "event-online-v1"));
    }

    private AiClassificationProperties propertiesWithBaseUrl(String baseUrl) {
        return new AiClassificationProperties(
                URI.create(baseUrl), "key", "gpt-4o-mini-2024-07-18", 3_000, 10_000,
                new AiClassificationProperties.Classification(
                        true, 100, 5, 2, "privacy-ai-v1",
                        "event-online-review-v1", "event-online-ko-v1", "event-online-v1"));
    }

    private UserConsent consent(String action, String policyVersion) {
        return UserConsent.builder()
                .consentEventId(UUID.randomUUID())
                .userId(USER_ID)
                .consentType("privacy")
                .policyVersion(policyVersion)
                .action(action)
                .isRequired(false)
                .idempotencyKey(UUID.randomUUID())
                .recordedAt(Instant.parse("2026-08-20T00:00:00Z"))
                .build();
    }
}
