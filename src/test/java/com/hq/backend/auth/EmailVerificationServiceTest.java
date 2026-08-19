package com.hq.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock private EmailVerificationTokenRepository tokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private VerificationEmailSender emailSender;

    @Test
    void 원문_token은_저장하지_않고_메일_링크로만_전달한_뒤_한번만_검증한다() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().userId(userId).email("verify@example.com")
                .nickname("verify").timezone("Asia/Seoul").createdAt(Instant.now()).accountStatus("active").build();
        EmailVerificationService service = new EmailVerificationService(tokenRepository, userRepository, emailSender);
        ReflectionTestUtils.setField(service, "tokenTtlMinutes", 30L);
        ReflectionTestUtils.setField(service, "resendCooldownSeconds", 60L);
        ReflectionTestUtils.setField(service, "verificationBaseUrl", "https://api.example.test");
        when(emailSender.isAvailable()).thenReturn(true);
        when(tokenRepository.save(any(EmailVerificationToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.issueAndSend(user);

        ArgumentCaptor<EmailVerificationToken> stored = ArgumentCaptor.forClass(EmailVerificationToken.class);
        org.mockito.Mockito.verify(tokenRepository).save(stored.capture());
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(emailSender).sendVerificationLink(org.mockito.Mockito.eq(user.getEmail()), link.capture());
        String rawToken = URI.create(link.getValue()).getQuery().substring("token=".length());
        assertThat(stored.getValue().getTokenHash()).isNotEqualTo(rawToken);
        assertThat(stored.getValue().getTokenHash()).hasSize(64);
        assertThat(Duration.between(stored.getValue().getCreatedAt(), stored.getValue().getExpiresAt()))
                .isEqualTo(Duration.ofMinutes(30));

        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.of(stored.getValue()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        service.verify(rawToken);

        assertThat(user.getEmailVerifiedAt()).isNotNull();
        assertThat(stored.getValue().getUsedAt()).isNotNull();
    }
}
