package com.hq.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hq.backend.auth.dto.GoogleLoginRequest;
import com.hq.backend.auth.dto.GoogleUserInfoResponse;
import com.hq.backend.auth.dto.LoginRequest;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.pushdevice.PushDeviceRepository;
import com.hq.backend.user.User;
import com.hq.backend.user.UserCredential;
import com.hq.backend.user.UserCredentialRepository;
import com.hq.backend.user.UserIdentity;
import com.hq.backend.user.UserIdentityRepository;
import com.hq.backend.user.UserRepository;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class AuthIdentityStatusTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;
    @Mock private RestClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;
    @Mock private UserRepository userRepository;
    @Mock private UserIdentityRepository userIdentityRepository;
    @Mock private UserCredentialRepository userCredentialRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PushDeviceRepository pushDeviceRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private EmailVerificationService emailVerificationService;
    @Mock private com.hq.backend.consent.UserConsentRepository userConsentRepository;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                userIdentityRepository,
                userCredentialRepository,
                refreshTokenRepository,
                pushDeviceRepository,
                passwordEncoder,
                jwtService,
                restClient,
                transactionTemplate,
                emailVerificationService,
                userConsentRepository);
        ReflectionTestUtils.setField(authService, "consentPolicyVersion", "v1");
        ReflectionTestUtils.setField(authService, "googleTokenInfoUrl", "https://oauth2.googleapis.com/tokeninfo");
        ReflectionTestUtils.setField(authService, "googleClientId", "ensom-client-id");
    }

    @Test
    void revoked_email_identity는_올바른_비밀번호로도_로그인할_수_없다() {
        UUID userId = UUID.randomUUID();
        User user = user(userId, "revoked@example.com");
        UserCredential credential = credential(userId);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(userCredentialRepository.findById(userId)).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("correct-password", credential.getPasswordHash())).thenReturn(true);
        when(emailVerificationService.isEnabled()).thenReturn(false);
        when(userIdentityRepository.findByUserIdAndProviderAndRevokedAtIsNull(userId, "email"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest(user.getEmail(), "correct-password")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_CREDENTIALS");
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void active_email_identity는_정상적으로_로그인한다() {
        UUID userId = UUID.randomUUID();
        User user = user(userId, "active@example.com");
        UserCredential credential = credential(userId);
        UserIdentity identity = identity(userId, "email", user.getEmail(), null);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(userCredentialRepository.findById(userId)).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("correct-password", credential.getPasswordHash())).thenReturn(true);
        when(emailVerificationService.isEnabled()).thenReturn(false);
        when(userIdentityRepository.findByUserIdAndProviderAndRevokedAtIsNull(userId, "email"))
                .thenReturn(Optional.of(identity));
        stubTokens(userId);

        assertThat(authService.login(new LoginRequest(user.getEmail(), "correct-password")).accessToken())
                .isEqualTo("access-token");
    }

    @Test
    void revoked_google_identity는_신규_가입으로_우회하지_못한다() {
        UUID userId = UUID.randomUUID();
        GoogleUserInfoResponse info = stubGoogleResponse("revoked-google", "revoked-google@example.com");
        when(userIdentityRepository.findByProviderAndProviderUid("google", info.sub()))
                .thenReturn(Optional.of(identity(userId, "google", info.sub(), Instant.now())));

        assertThatThrownBy(() -> authService.loginWithGoogle(new GoogleLoginRequest("id-token")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_CREDENTIALS");
        verify(transactionTemplate, never()).execute(any());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void active_google_identity는_정상적으로_로그인한다() {
        UUID userId = UUID.randomUUID();
        User user = user(userId, "active-google@example.com");
        GoogleUserInfoResponse info = stubGoogleResponse("active-google", user.getEmail());
        when(userIdentityRepository.findByProviderAndProviderUid("google", info.sub()))
                .thenReturn(Optional.of(identity(userId, "google", info.sub(), null)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        stubTokens(userId);

        assertThat(authService.loginWithGoogle(new GoogleLoginRequest("id-token")).accessToken())
                .isEqualTo("access-token");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private GoogleUserInfoResponse stubGoogleResponse(String subject, String email) {
        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn((RestClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        GoogleUserInfoResponse info = new GoogleUserInfoResponse(subject, email, "ensom-client-id");
        when(responseSpec.body(GoogleUserInfoResponse.class)).thenReturn(info);
        return info;
    }

    private void stubTokens(UUID userId) {
        when(jwtService.generateAccessToken(userId)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(userId)).thenReturn("refresh-token");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(3600L);
        when(jwtService.getRefreshTokenExpirationMs()).thenReturn(3_600_000L);
    }

    private User user(UUID userId, String email) {
        User user = User.builder()
                .email(email)
                .nickname("tester")
                .timezone("Asia/Seoul")
                .createdAt(Instant.now())
                .emailVerifiedAt(Instant.now())
                .accountStatus("active")
                .build();
        ReflectionTestUtils.setField(user, "userId", userId);
        return user;
    }

    private UserCredential credential(UUID userId) {
        return UserCredential.builder()
                .userId(userId)
                .passwordHash("encoded-password")
                .passwordAlgo("argon2id")
                .passwordUpdatedAt(Instant.now())
                .failedAttempts((short) 0)
                .build();
    }

    private UserIdentity identity(UUID userId, String provider, String providerUid, Instant revokedAt) {
        return UserIdentity.builder()
                .userId(userId)
                .provider(provider)
                .providerUid(providerUid)
                .linkedAt(Instant.now())
                .revokedAt(revokedAt)
                .build();
    }
}
