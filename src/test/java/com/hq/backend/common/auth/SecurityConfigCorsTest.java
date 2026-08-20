package com.hq.backend.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hq.backend.auth.JwtService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

class SecurityConfigCorsTest {

    private final SecurityConfig securityConfig = new SecurityConfig(mock(JwtService.class));

    @Test
    void Firebase_Hosting_exact_origins만_허용하고_credentialed_CORS는_비활성화한다() {
        var request = new MockHttpServletRequest("OPTIONS", "/auth/email/login");
        CorsConfiguration config = securityConfig.corsConfigurationSource().getCorsConfiguration(request);

        assertThat(config.getAllowedOrigins()).containsExactly(
                "https://ensom-10da2.web.app",
                "https://ensom-10da2.firebaseapp.com"
        );
        assertThat(config.getAllowedOriginPatterns()).isNullOrEmpty();
        assertThat(config.getAllowedHeaders()).containsExactly(
                "Authorization",
                "Content-Type",
                "Idempotency-Key",
                "X-App-Version"
        );
        assertThat(config.getAllowCredentials()).isFalse();
        assertThat(config.checkOrigin("https://ensom-10da2.web.app"))
                .isEqualTo("https://ensom-10da2.web.app");
        assertThat(config.checkOrigin("https://untrusted.example")).isNull();
    }
}
