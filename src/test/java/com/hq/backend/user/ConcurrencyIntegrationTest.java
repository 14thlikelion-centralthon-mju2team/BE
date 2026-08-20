package com.hq.backend.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.hq.backend.auth.PasswordResetService;
import com.hq.backend.auth.PasswordResetToken;
import com.hq.backend.auth.PasswordResetTokenRepository;
import com.hq.backend.common.util.TokenHashUtil;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 동시성 회귀 테스트 — PESSIMISTIC_WRITE 락이 race condition을 방지하는지 검증.
 * CI의 PostgreSQL services에서 실행된다.
 */
@SpringBootTest
@DirtiesContext
class ConcurrencyIntegrationTest {

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCredentialRepository userCredentialRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private AccountManagementService accountManagementService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * #190: 두 요청이 동시에 각각의 provider를 해제하면 하나는 실패해야 한다.
     */
    @Test
    void 동시에_두_provider를_해제하면_하나는_LAST_IDENTITY로_실패한다() throws Exception {
        // Given: 사용자 + email identity + google identity
        UUID userId = createTestUser("concurrency-provider-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com");
        UUID emailIdentityId = createIdentity(userId, "email", "concurrency-provider@test.com");
        UUID googleIdentityId = createIdentity(userId, "google", "google-uid-concurrent");

        // When: 동시에 두 identity를 해제
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        Future<?> f1 = executor.submit(() -> {
            try {
                latch.await();
                accountManagementService.unlinkProvider(userId, emailIdentityId);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            }
        });

        Future<?> f2 = executor.submit(() -> {
            try {
                latch.await();
                accountManagementService.unlinkProvider(userId, googleIdentityId);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            }
        });

        latch.countDown(); // 동시 실행
        f1.get();
        f2.get();
        executor.shutdown();

        // Then: 하나만 성공, 하나는 실패 (최소 1개 identity 유지)
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(1);

        List<UserIdentity> remaining = userIdentityRepository.findAllByUserIdAndRevokedAtIsNull(userId);
        assertThat(remaining).hasSize(1);
    }

    /**
     * #193: 같은 reset 토큰으로 동시에 비밀번호를 변경하면 하나만 성공해야 한다.
     */
    @Test
    void 동시에_같은_토큰으로_비밀번호_재설정하면_하나만_성공한다() throws Exception {
        // Given: 사용자 + credential + reset token
        UUID userId = createTestUser("concurrency-reset-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com");
        createCredential(userId, "OldPassword123!");
        String rawToken = UUID.randomUUID().toString();
        createResetToken(userId, rawToken);

        // When: 동시에 같은 토큰으로 재설정
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final String newPassword = "NewPassword" + i + "!!";
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    passwordResetService.executeReset(rawToken, newPassword);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) { f.get(); }
        executor.shutdown();

        // Then: 하나만 성공
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(1);
    }

    // ─── Helpers ───

    private UUID createTestUser(String email) {
        User user = userRepository.save(User.builder()
                .email(email)
                .nickname("test-" + UUID.randomUUID().toString().substring(0, 6))
                .timezone("Asia/Seoul")
                .createdAt(Instant.now())
                .emailVerifiedAt(Instant.now())
                .accountStatus("active")
                .build());
        return user.getUserId();
    }

    private UUID createIdentity(UUID userId, String provider, String providerUid) {
        UserIdentity identity = userIdentityRepository.save(UserIdentity.builder()
                .userId(userId)
                .provider(provider)
                .providerUid(providerUid)
                .linkedAt(Instant.now())
                .build());
        return identity.getIdentityId();
    }

    private void createCredential(UUID userId, String password) {
        userCredentialRepository.save(UserCredential.builder()
                .userId(userId)
                .passwordHash(passwordEncoder.encode(password))
                .passwordAlgo("bcrypt")
                .passwordUpdatedAt(Instant.now())
                .failedAttempts((short) 0)
                .build());
    }

    private void createResetToken(UUID userId, String rawToken) {
        tokenRepository.save(PasswordResetToken.builder()
                .userId(userId)
                .tokenHash(TokenHashUtil.sha256(rawToken))
                .type("password_reset")
                .expiresAt(Instant.now().plusSeconds(1800))
                .build());
    }
}
