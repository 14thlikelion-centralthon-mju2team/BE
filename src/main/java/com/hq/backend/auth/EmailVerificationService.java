package com.hq.backend.auth;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final VerificationEmailSender verificationEmailSender;

    @Value("${app.email-verification.enabled:true}")
    private boolean emailVerificationEnabled;

    @Value("${app.email-verification.token-ttl-minutes:30}")
    private long tokenTtlMinutes;

    @Value("${app.email-verification.resend-cooldown-seconds:60}")
    private long resendCooldownSeconds;

    @Value("${app.email-verification.base-url}")
    private String verificationBaseUrl;

    public boolean isEnabled() {
        return emailVerificationEnabled;
    }

    @Transactional
    public void issueAndSend(User user) {
        if (!verificationEmailSender.isAvailable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE",
                    "이메일 인증 서비스를 일시적으로 사용할 수 없습니다.");
        }
        if (user.getEmailVerifiedAt() != null) {
            return;
        }

        Instant now = Instant.now();
        invalidateActiveTokens(user.getUserId(), now);
        String rawToken = newToken();
        tokenRepository.save(EmailVerificationToken.builder()
                .userId(user.getUserId())
                .tokenHash(hash(rawToken))
                .expiresAt(now.plus(Duration.ofMinutes(tokenTtlMinutes)))
                .createdAt(now)
                .build());

        String baseUrl = verificationBaseUrl.endsWith("/")
                ? verificationBaseUrl.substring(0, verificationBaseUrl.length() - 1)
                : verificationBaseUrl;
        verificationEmailSender.sendVerificationLink(user.getEmail(),
                baseUrl + "/auth/email/verify?token=" + rawToken);
    }

    /** Always returns normally for an unknown or already verified address to avoid account enumeration. */
    @Transactional
    public void resend(String email) {
        if (!verificationEmailSender.isAvailable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE",
                    "이메일 인증 서비스를 일시적으로 사용할 수 없습니다.");
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || user.getEmailVerifiedAt() != null) {
            return;
        }

        Instant now = Instant.now();
        EmailVerificationToken latest = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getUserId()).orElse(null);
        if (latest != null && latest.getCreatedAt().plusSeconds(resendCooldownSeconds).isAfter(now)) {
            return;
        }
        issueAndSend(user);
    }

    @Transactional
    public void verify(String rawToken) {
        EmailVerificationToken token = tokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(this::invalidToken);
        Instant now = Instant.now();
        if (token.getUsedAt() != null || token.getInvalidatedAt() != null || !token.getExpiresAt().isAfter(now)) {
            throw invalidToken();
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(this::invalidToken);
        user.setEmailVerifiedAt(now);
        token.setUsedAt(now);
        invalidateActiveTokens(user.getUserId(), now);
    }

    private void invalidateActiveTokens(java.util.UUID userId, Instant now) {
        List<EmailVerificationToken> active = tokenRepository.findByUserIdAndUsedAtIsNullAndInvalidatedAtIsNull(userId);
        active.forEach(token -> token.setInvalidatedAt(now));
    }

    private ApiException invalidToken() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_TOKEN",
                "인증 링크가 유효하지 않거나 만료되었습니다.");
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
