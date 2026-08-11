package com.hq.backend.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hq.backend.calendar.dto.BusyBlockResponse;
import com.hq.backend.calendar.dto.CalendarConnectionResponse;
import com.hq.backend.calendar.dto.ConnectCalendarRequest;
import com.hq.backend.calendar.dto.GoogleCalendarEvent;
import com.hq.backend.calendar.dto.GoogleCalendarEventsResponse;
import com.hq.backend.calendar.dto.GoogleEventDateTime;
import com.hq.backend.calendar.dto.GoogleTokenResponse;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.UserEvent;
import com.hq.backend.event.UserEventRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class CalendarServiceTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private RestClient.RequestBodySpec requestBodySpec;
    @Mock private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock private RestClient.RequestHeadersSpec requestHeadersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @Mock private CalendarConnectionRepository calendarConnectionRepository;
    @Mock private UserEventRepository userEventRepository;
    @Mock private BytesEncryptor calendarTokenEncryptor;

    private CalendarService calendarService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        calendarService =
                new CalendarService(calendarConnectionRepository, userEventRepository, calendarTokenEncryptor, restClient);
        ReflectionTestUtils.setField(calendarService, "googleTokenUrl", "https://oauth2.googleapis.com/token");
        ReflectionTestUtils.setField(calendarService, "googleClientId", "vium-client-id");
        ReflectionTestUtils.setField(calendarService, "googleClientSecret", "vium-client-secret");
        ReflectionTestUtils.setField(
                calendarService, "googleCalendarEventsUrl", "https://www.googleapis.com/calendar/v3/calendars/primary/events");

        // disconnect() 테스트는 이 체인을 안 써서, 안 쓰는 테스트 입장에선 "unnecessary stubbing"이
        // 된다 — lenient()로 그 경고를 끈다.
        // body(Object)/body(StreamingHttpOutputMessage.Body) 두 오버로드가 있어서 타입 힌트 없는
        // any()를 쓰면 더 구체적인 쪽으로 스텁이 걸려버린다 — any(Object.class)로 오버로드 고정.
        lenient().when(restClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.uri(anyStringSafe())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        // GET 체인 (getDensity가 구글 캘린더 이벤트를 조회할 때 사용) — 위와 같은 이유로 lenient.
        lenient().when(restClient.get()).thenReturn(requestHeadersUriSpec);
        lenient().when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersSpec);
        lenient().when(requestHeadersSpec.header(any(), any())).thenReturn(requestHeadersSpec);
        lenient().when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
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

    @Test
    void 캘린더_연결이_없으면_user_events만_반환한다() {
        UUID userId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 8, 14);
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.empty());
        UserEvent event = UserEvent.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .startsAt(Instant.parse("2026-08-14T05:00:00Z"))
                .endsAt(Instant.parse("2026-08-14T06:00:00Z"))
                .createdAt(Instant.now())
                .build();
        when(userEventRepository.findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of(event));

        List<BusyBlockResponse> result = calendarService.getDensity(userId, date);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).source()).isEqualTo("user_event");
    }

    @Test
    void 연결이_해제된_상태면_구글_호출을_안_하고_user_events만_반환한다() {
        UUID userId = UUID.randomUUID();
        CalendarConnection revoked = CalendarConnection.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .provider("google")
                .refreshTokenEnc(new byte[] {1})
                .scope("calendar.readonly")
                .connectedAt(Instant.now())
                .revokedAt(Instant.now())
                .build();
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.of(revoked));
        when(userEventRepository.findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of());

        List<BusyBlockResponse> result = calendarService.getDensity(userId, LocalDate.of(2026, 8, 14));

        assertThat(result).isEmpty();
        verify(calendarTokenEncryptor, org.mockito.Mockito.never()).decrypt(any());
    }

    @Test
    void 구글_토큰_갱신이_실패해도_전체_요청은_안_죽고_user_events만_반환한다() {
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = CalendarConnection.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .provider("google")
                .refreshTokenEnc(new byte[] {1, 2, 3})
                .scope("calendar.readonly")
                .connectedAt(Instant.now())
                .build();
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.of(connection));
        when(calendarTokenEncryptor.decrypt(any())).thenReturn("refresh-token".getBytes(StandardCharsets.UTF_8));
        when(responseSpec.body(GoogleTokenResponse.class)).thenThrow(new RestClientException("boom"));
        when(userEventRepository.findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of());

        List<BusyBlockResponse> result = calendarService.getDensity(userId, LocalDate.of(2026, 8, 14));

        assertThat(result).isEmpty();
    }

    @Test
    void 구글_이벤트와_user_events를_합쳐서_시간순으로_반환하고_종일일정은_건너뛴다() {
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = CalendarConnection.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .provider("google")
                .refreshTokenEnc(new byte[] {1, 2, 3})
                .scope("calendar.readonly")
                .connectedAt(Instant.now())
                .build();
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.of(connection));
        when(calendarTokenEncryptor.decrypt(any())).thenReturn("refresh-token".getBytes(StandardCharsets.UTF_8));
        when(responseSpec.body(GoogleTokenResponse.class))
                .thenReturn(new GoogleTokenResponse("access-token", null, "calendar.readonly", 3600L));

        GoogleCalendarEvent timedEvent = new GoogleCalendarEvent(
                new GoogleEventDateTime(Instant.parse("2026-08-14T09:00:00Z")),
                new GoogleEventDateTime(Instant.parse("2026-08-14T10:00:00Z")));
        GoogleCalendarEvent allDayEvent = new GoogleCalendarEvent(new GoogleEventDateTime(null), new GoogleEventDateTime(null));
        when(responseSpec.body(GoogleCalendarEventsResponse.class))
                .thenReturn(new GoogleCalendarEventsResponse(List.of(timedEvent, allDayEvent)));

        UserEvent userEvent = UserEvent.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .startsAt(Instant.parse("2026-08-14T05:00:00Z"))
                .endsAt(Instant.parse("2026-08-14T06:00:00Z"))
                .createdAt(Instant.now())
                .build();
        when(userEventRepository.findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of(userEvent));

        List<BusyBlockResponse> result = calendarService.getDensity(userId, LocalDate.of(2026, 8, 14));

        assertThat(result).hasSize(2); // 종일 일정은 제외되고 2건만
        assertThat(result.get(0).source()).isEqualTo("user_event"); // 05시가 09시보다 먼저
        assertThat(result.get(1).source()).isEqualTo("google");
    }
}
