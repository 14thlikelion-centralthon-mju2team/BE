package com.hq.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hq.backend.common.exception.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private final JwtService jwtService =
            new JwtService("test-secret-key-at-least-32-bytes-long", 3600000L, 1209600000L);

    @Test
    void access_token으로_getUserId를_통과한다() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId);

        assertThat(jwtService.getUserId(token)).isEqualTo(userId);
    }

    @Test
    void refresh_token은_getUserId에서_거부된다() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateRefreshToken(userId);

        assertThatThrownBy(() -> jwtService.getUserId(token))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_TOKEN");
    }
}
