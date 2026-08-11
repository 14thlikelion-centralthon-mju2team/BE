package com.hq.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.hq.backend.auth.dto.GoogleLoginRequest;
import com.hq.backend.auth.dto.GoogleUserInfoResponse;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock private RestClient.RequestHeadersSpec requestHeadersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService, restClient);
        ReflectionTestUtils.setField(authService, "googleTokenInfoUrl", "https://oauth2.googleapis.com/tokeninfo");
        ReflectionTestUtils.setField(authService, "googleClientId", "vium-client-id");

        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    void 신규_구글_유저는_로그인시_가입되고_토큰을_발급받는다() {
        GoogleUserInfoResponse info = new GoogleUserInfoResponse("google-sub-1", "new@example.com", "vium-client-id");
        when(responseSpec.body(GoogleUserInfoResponse.class)).thenReturn(info);
        when(userRepository.findByProviderAndProviderUid("google", "google-sub-1")).thenReturn(Optional.empty());
        UUID userId = UUID.randomUUID();
        when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class)))
                .thenAnswer(invocation -> {
                    User user = invocation.getArgument(0);
                    ReflectionTestUtils.setField(user, "id", userId);
                    return user;
                });
        when(jwtService.generateAccessToken(userId)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(userId)).thenReturn("refresh-token");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(3600L);

        var result = authService.loginWithGoogle(new GoogleLoginRequest("id-token"));

        assertThat(result.accessToken()).isEqualTo("access-token");
    }

    @Test
    void aud가_설정된_클라이언트id와_다르면_거부한다() {
        GoogleUserInfoResponse info = new GoogleUserInfoResponse("google-sub-2", "other@example.com", "other-app-client-id");
        when(responseSpec.body(GoogleUserInfoResponse.class)).thenReturn(info);

        assertThatThrownBy(() -> authService.loginWithGoogle(new GoogleLoginRequest("id-token")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_GOOGLE_TOKEN");
    }
}
