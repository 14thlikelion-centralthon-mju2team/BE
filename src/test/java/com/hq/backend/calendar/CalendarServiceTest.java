package com.hq.backend.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hq.backend.calendar.dto.CalendarConnectionResponse;
import com.hq.backend.calendar.dto.ConnectCalendarRequest;
import com.hq.backend.calendar.dto.GoogleTokenResponse;
import com.hq.backend.common.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class CalendarServiceTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private RestClient.RequestBodySpec requestBodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @Mock private CalendarConnectionRepository calendarConnectionRepository;
    @Mock private BytesEncryptor calendarTokenEncryptor;

    private CalendarService calendarService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        calendarService = new CalendarService(calendarConnectionRepository, calendarTokenEncryptor, restClient);
        ReflectionTestUtils.setField(calendarService, "googleTokenUrl", "https://oauth2.googleapis.com/token");
        ReflectionTestUtils.setField(calendarService, "googleClientId", "vium-client-id");
        ReflectionTestUtils.setField(calendarService, "googleClientSecret", "vium-client-secret");

        // disconnect() 테스트는 이 체인을 안 써서, 안 쓰는 테스트 입장에선 "unnecessary stubbing"이
        // 된다 — lenient()로 그 경고를 끈다.
        // body(Object)/body(StreamingHttpOutputMessage.Body) 두 오버로드가 있어서 타입 힌트 없는
        // any()를 쓰면 더 구체적인 쪽으로 스텁이 걸려버린다 — any(Object.class)로 오버로드 고정.
        lenient().when(restClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.uri(anyStringSafe())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    }

    private static String anyStringSafe() {
        return org.mockito.ArgumentMatchers.anyString();
    }

    @Test
    void refresh_token을_받으면_암호화해서_신규_연결을_생성한다() {
        UUID userId = UUID.randomUUID();
        when(responseSpec.body(GoogleTokenResponse.class))
                .thenReturn(new GoogleTokenResponse("access", "refresh-token-value", "calendar.readonly", 3600L));
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.empty());
        when(calendarTokenEncryptor.encrypt("refresh-token-value".getBytes(StandardCharsets.UTF_8)))
                .thenReturn(new byte[] {1, 2, 3});

        CalendarConnectionResponse response = calendarService.connect(userId, new ConnectCalendarRequest("auth-code"));

        assertThat(response.provider()).isEqualTo("google");
        assertThat(response.scope()).isEqualTo("calendar.readonly");
        verify(calendarConnectionRepository).save(any(CalendarConnection.class));
    }

    @Test
    void refresh_token이_없고_기존_연결도_없으면_거부한다() {
        UUID userId = UUID.randomUUID();
        when(responseSpec.body(GoogleTokenResponse.class))
                .thenReturn(new GoogleTokenResponse("access", null, "calendar.readonly", 3600L));
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> calendarService.connect(userId, new ConnectCalendarRequest("auth-code")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "REFRESH_TOKEN_MISSING");
    }

    @Test
    void refresh_token이_없어도_기존_연결이_있으면_기존_토큰을_유지한채_갱신한다() {
        UUID userId = UUID.randomUUID();
        CalendarConnection existing = CalendarConnection.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .provider("google")
                .refreshTokenEnc(new byte[] {9, 9, 9})
                .scope("calendar.readonly")
                .connectedAt(Instant.now().minusSeconds(86400))
                .revokedAt(Instant.now().minusSeconds(3600)) // 재연결 시나리오
                .build();
        when(responseSpec.body(GoogleTokenResponse.class))
                .thenReturn(new GoogleTokenResponse("access", null, "calendar.readonly", 3600L));
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.of(existing));

        calendarService.connect(userId, new ConnectCalendarRequest("auth-code"));

        assertThat(existing.getRefreshTokenEnc()).containsExactly(9, 9, 9); // 그대로 유지
        assertThat(existing.getRevokedAt()).isNull(); // 재연결로 revoke 해제
    }

    @Test
    void 연결_안_된_상태에서_해제하면_404() {
        UUID userId = UUID.randomUUID();
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> calendarService.disconnect(userId))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "CALENDAR_NOT_CONNECTED");
    }

    @Test
    void 해제하면_revokedAt이_기록된다() {
        UUID userId = UUID.randomUUID();
        CalendarConnection existing = CalendarConnection.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .provider("google")
                .refreshTokenEnc(new byte[] {1})
                .scope("calendar.readonly")
                .connectedAt(Instant.now())
                .build();
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.of(existing));

        calendarService.disconnect(userId);

        assertThat(existing.getRevokedAt()).isNotNull();
    }
}
