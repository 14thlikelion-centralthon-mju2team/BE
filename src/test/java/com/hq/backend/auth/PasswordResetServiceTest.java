package com.hq.backend.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.common.util.TokenHashUtil;
import com.hq.backend.user.UserCredentialRepository;
import com.hq.backend.user.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserCredentialRepository userCredentialRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private VerificationEmailSender verificationEmailSender;

    private PasswordResetService passwordResetService;

    @BeforeEach
    void setUp() {
        passwordResetService = new PasswordResetService(
                tokenRepository,
                userRepository,
                userCredentialRepository,
                refreshTokenRepository,
                passwordEncoder,
                verificationEmailSender);
    }

    @Test
    void email_change_토큰으로_비밀번호를_재설정할_수_없다() {
        String rawToken = "email-change-token";
        UUID userId = UUID.randomUUID();
        PasswordResetToken token = PasswordResetToken.builder()
                .userId(userId)
                .tokenHash(TokenHashUtil.sha256(rawToken))
                .type("email_change")
                .newEmail("new@example.com")
                .expiresAt(Instant.now().plusSeconds(1800))
                .createdAt(Instant.now())
                .build();
        when(tokenRepository.findByTokenHashForUpdate(TokenHashUtil.sha256(rawToken)))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> passwordResetService.executeReset(rawToken, "NewPassword123!"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_TOKEN");

        verify(userCredentialRepository, never()).findById(userId);
        verify(refreshTokenRepository, never()).revokeAllByUserId(userId);
        verify(passwordEncoder, never()).encode("NewPassword123!");
    }
}
