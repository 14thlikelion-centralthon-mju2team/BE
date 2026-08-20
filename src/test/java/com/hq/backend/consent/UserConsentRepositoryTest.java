package com.hq.backend.consent;

import static org.assertj.core.api.Assertions.assertThat;

import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class UserConsentRepositoryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserConsentRepository userConsentRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void latest_privacy_consent_uses_recorded_at_then_consent_event_id_descending() {
        UUID userId = userRepository.saveAndFlush(User.builder()
                .email("consent-order-" + UUID.randomUUID() + "@example.com")
                .nickname("consent-order-" + UUID.randomUUID().toString().substring(0, 8))
                .timezone("Asia/Seoul")
                .createdAt(Instant.parse("2026-08-20T00:00:00Z"))
                .accountStatus("active")
                .build()).getUserId();
        Instant recordedAt = Instant.parse("2026-08-20T00:00:00Z");
        insertConsent(UUID.fromString("00000000-0000-0000-0000-000000000001"), userId, recordedAt, "agreed");
        insertConsent(UUID.fromString("00000000-0000-0000-0000-000000000002"), userId, recordedAt, "revoked");

        assertThat(userConsentRepository
                .findFirstByUserIdAndConsentTypeOrderByRecordedAtDescConsentEventIdDesc(userId, "privacy"))
                .get()
                .extracting(UserConsent::getConsentEventId, UserConsent::getAction)
                .containsExactly(UUID.fromString("00000000-0000-0000-0000-000000000002"), "revoked");
    }

    private void insertConsent(UUID consentEventId, UUID userId, Instant recordedAt, String action) {
        jdbcTemplate.update("""
                insert into user_consent (
                    consent_event_id, user_id, consent_type, policy_version, action, is_required,
                    idempotency_key, recorded_at
                ) values (?, ?, 'privacy', 'privacy-ai-v1', ?, false, ?, ?)
                """, consentEventId, userId, action, UUID.randomUUID(), Timestamp.from(recordedAt));
    }
}
