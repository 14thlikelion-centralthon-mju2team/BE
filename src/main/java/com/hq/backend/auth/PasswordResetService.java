package com.hq.backend.auth;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.common.util.TokenHashUtil;
import com.hq.backend.user.User;
import com.hq.backend.user.UserCredential;
import com.hq.backend.user.UserCredentialRepository;
import com.hq.backend.user.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 재설정 토큰 발급/검증 서비스.
 * EmailVerificationService 패턴을 따르되 별도 테이블(password_reset_token)을 사용한다.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final long TOKEN_TTL_MINUTES = 30;

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final VerificationEmailSender verificationEmailSender;

    @Value("${app.email-verification.base-url}")
    private String baseUrl;

    /**
     * 비밀번호 재설정 요청. 계정 유무와 무관하게 정상 반환(TR-14 계정 열거 방지).
     */
    @Transactional
    public void requestReset(String email) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return; // 계정 유무 노출 방지
        }

        // 이메일 발송 불가 시 조용히 반환 (계정 존재 자체를 숨기는 정책)
        if (!verificationEmailSender.isAvailable()) {
            return;
        }

        // 이전 미소비 토큰 무효화
        tokenRepository.consumeAllActiveByUserIdAndType(user.getUserId(), "password_reset");

        Instant now = Instant.now();
        String rawToken = newToken();
        tokenRepository.save(PasswordResetToken.builder()
                .userId(user.getUserId())
                .tokenHash(TokenHashUtil.sha256(rawToken))
                .type("password_reset")
                .expiresAt(now.plus(Duration.ofMinutes(TOKEN_TTL_MINUTES)))
                .createdAt(now)
                .build());

        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        verificationEmailSender.sendVerificationLink(user.getEmail(),
                normalizedBase + "/auth/password/reset?token=" + rawToken);
    }

    /**
     * 토큰 검증 → 비밀번호 변경 → 전 기기 세션 무효화 → 토큰 소비.
     */
    @Transactional
    public void executeReset(String rawToken, String newPassword) {
        validatePasswordLength(newPassword);

        PasswordResetToken token = tokenRepository.findByTokenHashForUpdate(TokenHashUtil.sha256(rawToken))
                .orElseThrow(this::invalidToken);
        Instant now = Instant.now();
        if (token.getConsumedAt() != null || !token.getExpiresAt().isAfter(now)) {
            throw invalidToken();
        }
        if (!"password_reset".equals(token.getType())) {
            throw invalidToken();
        }

        UserCredential credential = userCredentialRepository.findById(token.getUserId())
                .orElseThrow(this::invalidToken);

        credential.setPasswordHash(passwordEncoder.encode(newPassword));
        credential.setPasswordUpdatedAt(now);

        // 전 기기 세션 무효화
        refreshTokenRepository.revokeAllByUserId(token.getUserId());

        // 토큰 소비
        token.setConsumedAt(now);
    }

    /**
     * 이메일 변경 요청 토큰 발급. type='email_change'로 저장하고 새 이메일 주소로 발송.
     */
    @Transactional
    public void issueEmailChangeToken(UUID userId, String newEmail) {
        if (!verificationEmailSender.isAvailable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE",
                    "이메일 인증 서비스를 일시적으로 사용할 수 없습니다.");
        }

        // 이전 미소비 토큰 무효화
        tokenRepository.consumeAllActiveByUserIdAndType(userId, "email_change");

        Instant now = Instant.now();
        String rawToken = newToken();
        tokenRepository.save(PasswordResetToken.builder()
                .userId(userId)
                .tokenHash(TokenHashUtil.sha256(rawToken))
                .type("email_change")
                .newEmail(newEmail)
                .expiresAt(now.plus(Duration.ofMinutes(TOKEN_TTL_MINUTES)))
                .createdAt(now)
                .build());

        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        verificationEmailSender.sendVerificationLink(newEmail,
                normalizedBase + "/me/email/change-confirm?token=" + rawToken);
    }

    /**
     * 이메일 변경 확인. 토큰 검증 → 소비. 토큰에 저장된 정보를 반환.
     */
    @Transactional
    public EmailChangeResult consumeEmailChangeToken(String rawToken) {
        PasswordResetToken token = tokenRepository.findByTokenHashForUpdate(TokenHashUtil.sha256(rawToken))
                .orElseThrow(this::invalidToken);
        Instant now = Instant.now();
        if (token.getConsumedAt() != null || !token.getExpiresAt().isAfter(now)) {
            throw invalidToken();
        }
        if (!"email_change".equals(token.getType())) {
            throw invalidToken();
        }
        token.setConsumedAt(now);
        return new EmailChangeResult(token.getUserId(), token.getNewEmail());
    }

    public record EmailChangeResult(UUID userId, String newEmail) {
    }

    private void validatePasswordLength(String password) {
        if (password == null || password.length() < 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD",
                    "비밀번호는 10자 이상이어야 합니다.");
        }
    }

    private ApiException invalidToken() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN",
                "토큰이 유효하지 않거나 만료되었습니다.");
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
