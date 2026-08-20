package com.hq.backend.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hq.backend.calendar.dto.CalendarConnectionResponse;
import com.hq.backend.calendar.dto.ConnectCalendarRequest;
import com.hq.backend.calendar.dto.GoogleBusyEvent;
import com.hq.backend.calendar.dto.GoogleBusyEventsResponse;
import com.hq.backend.calendar.dto.GoogleEventDateTime;
import com.hq.backend.calendar.dto.GoogleTokenResponse;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
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
    @Mock private CalendarSourceRepository calendarSourceRepository;
    @Mock private EventRepository eventRepository;
    @Mock private BytesEncryptor calendarTokenEncryptor;
    @Mock private TransactionTemplate transactionTemplate;

    private CalendarService calendarService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        calendarService = new CalendarService(
                calendarConnectionRepository, calendarSourceRepository, eventRepository,
                calendarTokenEncryptor, restClient, transactionTemplate);
        lenient().when(calendarSourceRepository.findByCalendarConnectionIdAndIsDefaultTrueAndDeletedAtIsNull(any()))
                .thenReturn(Optional.empty());
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        ReflectionTestUtils.setField(calendarService, "googleTokenUrl", "https://oauth2.googleapis.com/token");
        ReflectionTestUtils.setField(calendarService, "googleClientId", "ensom-client-id");
        ReflectionTestUtils.setField(calendarService, "googleClientSecret", "ensom-client-secret");
        ReflectionTestUtils.setField(
                calendarService, "googleCalendarEventsUrl", "https://www.googleapis.com/calendar/v3/calendars/primary/events");

        lenient().when(restClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.uri(anyStringSafe())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        lenient().when(restClient.get()).thenReturn(requestHeadersUriSpec);
        lenient().when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersSpec);
        lenient().when(requestHeadersSpec.header(any(), any())).thenReturn(requestHeadersSpec);
        lenient().when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    }

    private static String anyStringSafe() {
        return org.mockito.ArgumentMatchers.anyString();
    }

    // 서명 검증 없이 payload만 읽으므로 header·signature는 아무 값이나 둬도 된다.
    private static String fakeIdToken(String externalAccountId) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + externalAccountId + "\"}").getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }

    @Test
    void refresh_token을_받으면_암호화해서_신규_연결을_생성한다() {
        UUID userId = UUID.randomUUID();
        when(responseSpec.body(GoogleTokenResponse.class))
                .thenReturn(new GoogleTokenResponse("access", "refresh-token-value", fakeIdToken("google-sub-1"), 3600L));
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.empty());
        when(calendarTokenEncryptor.encrypt("refresh-token-value".getBytes(StandardCharsets.UTF_8)))
                .thenReturn(new byte[] {1, 2, 3});

        CalendarConnectionResponse response = calendarService.connect(userId, new ConnectCalendarRequest("auth-code"));

        assertThat(response.provider()).isEqualTo("google");
        assertThat(response.externalAccountId()).isEqualTo("google-sub-1");
        verify(calendarConnectionRepository).save(any(CalendarConnection.class));
    }

    @Test
    void refresh_token이_없고_기존_연결도_없으면_거부한다() {
        UUID userId = UUID.randomUUID();
        when(responseSpec.body(GoogleTokenResponse.class))
                .thenReturn(new GoogleTokenResponse("access", null, fakeIdToken("google-sub-1"), 3600L));
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> calendarService.connect(userId, new ConnectCalendarRequest("auth-code")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "REFRESH_TOKEN_MISSING");
    }

    @Test
    void refresh_token이_없어도_기존_연결이_있으면_기존_토큰을_유지한채_갱신한다() {
        UUID userId = UUID.randomUUID();
        CalendarConnection existing = CalendarConnection.builder()
                .calendarConnectionId(UUID.randomUUID())
                .userId(userId)
                .provider("google")
                .externalAccountId("google-sub-1")
                .refreshTokenEnc(new byte[] {9, 9, 9})
                .connectedAt(Instant.now().minusSeconds(86400))
                .revokedAt(Instant.now().minusSeconds(3600)) // 재연결 시나리오
                .build();
        when(responseSpec.body(GoogleTokenResponse.class))
                .thenReturn(new GoogleTokenResponse("access", null, fakeIdToken("google-sub-1"), 3600L));
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
                .calendarConnectionId(UUID.randomUUID())
                .userId(userId)
                .provider("google")
                .externalAccountId("google-sub-1")
                .refreshTokenEnc(new byte[] {1})
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
        Event event = Event.builder()
                .eventId(UUID.randomUUID())
                .userId(userId)
                .sourceType("internal")
                .locationState("not_required")
                .status("planned")
                .startsAt(Instant.parse("2026-08-14T05:00:00Z"))
                .endsAt(Instant.parse("2026-08-14T06:00:00Z"))
                .createdAt(Instant.now())
                .build();
        when(eventRepository.findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of(event));

        var result = calendarService.getDensity(userId, date);

        assertThat(result.calendarSynced()).isTrue(); // 연동 자체가 없는 건 실패가 아니다
        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().get(0).source()).isEqualTo("user_event");
    }

    @Test
    void 연결이_해제된_상태면_구글_호출을_안_하고_user_events만_반환한다() {
        UUID userId = UUID.randomUUID();
        CalendarConnection revoked = CalendarConnection.builder()
                .calendarConnectionId(UUID.randomUUID())
                .userId(userId)
                .provider("google")
                .externalAccountId("google-sub-1")
                .refreshTokenEnc(new byte[] {1})
                .connectedAt(Instant.now())
                .revokedAt(Instant.now())
                .build();
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.of(revoked));
        when(eventRepository.findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of());

        var result = calendarService.getDensity(userId, LocalDate.of(2026, 8, 14));

        assertThat(result.calendarSynced()).isTrue(); // 해제된 연동은 실패가 아니라 "할 게 없음"
        assertThat(result.blocks()).isEmpty();
        verify(calendarTokenEncryptor, org.mockito.Mockito.never()).decrypt(any());
    }

    @Test
    void 구글_토큰_갱신이_실패해도_전체_요청은_안_죽고_user_events만_반환한다() {
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = CalendarConnection.builder()
                .calendarConnectionId(UUID.randomUUID())
                .userId(userId)
                .provider("google")
                .externalAccountId("google-sub-1")
                .refreshTokenEnc(new byte[] {1, 2, 3})
                .connectedAt(Instant.now())
                .build();
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.of(connection));
        when(calendarTokenEncryptor.decrypt(any())).thenReturn("refresh-token".getBytes(StandardCharsets.UTF_8));
        when(responseSpec.body(GoogleTokenResponse.class)).thenThrow(new RestClientException("boom"));
        when(eventRepository.findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of());

        var result = calendarService.getDensity(userId, LocalDate.of(2026, 8, 14));

        assertThat(result.calendarSynced()).isFalse(); // 연동은 있는데 이번엔 실패 — 구분돼야 함
        assertThat(result.blocks()).isEmpty();
    }

    @Test
    void refresh_token_복호화가_실패해도_전체_요청은_안_죽고_user_events만_반환한다() {
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = CalendarConnection.builder()
                .calendarConnectionId(UUID.randomUUID())
                .userId(userId)
                .provider("google")
                .externalAccountId("google-sub-1")
                .refreshTokenEnc(new byte[] {1, 2, 3})
                .connectedAt(Instant.now())
                .build();
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.of(connection));
        when(calendarTokenEncryptor.decrypt(any()))
                .thenThrow(new IllegalStateException("Unable to invoke Cipher due to bad padding"));
        when(eventRepository.findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of());

        var result = calendarService.getDensity(userId, LocalDate.of(2026, 8, 14));

        assertThat(result.calendarSynced()).isFalse();
        assertThat(result.blocks()).isEmpty();
    }

    @Test
    void 구글_이벤트와_user_events를_합쳐서_시간순으로_반환하고_종일일정은_건너뛴다() {
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = CalendarConnection.builder()
                .calendarConnectionId(UUID.randomUUID())
                .userId(userId)
                .provider("google")
                .externalAccountId("google-sub-1")
                .refreshTokenEnc(new byte[] {1, 2, 3})
                .connectedAt(Instant.now())
                .build();
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.of(connection));
        when(calendarTokenEncryptor.decrypt(any())).thenReturn("refresh-token".getBytes(StandardCharsets.UTF_8));
        when(responseSpec.body(GoogleTokenResponse.class))
                .thenReturn(new GoogleTokenResponse("access-token", null, null, 3600L));

        GoogleBusyEvent timedEvent = new GoogleBusyEvent(
                new GoogleEventDateTime(Instant.parse("2026-08-14T09:00:00Z")),
                new GoogleEventDateTime(Instant.parse("2026-08-14T10:00:00Z")));
        GoogleBusyEvent allDayEvent = new GoogleBusyEvent(new GoogleEventDateTime(null), new GoogleEventDateTime(null));
        when(responseSpec.body(GoogleBusyEventsResponse.class))
                .thenReturn(new GoogleBusyEventsResponse(List.of(timedEvent, allDayEvent), null));

        Event userEvent = Event.builder()
                .eventId(UUID.randomUUID())
                .userId(userId)
                .sourceType("internal")
                .locationState("not_required")
                .status("planned")
                .startsAt(Instant.parse("2026-08-14T05:00:00Z"))
                .endsAt(Instant.parse("2026-08-14T06:00:00Z"))
                .createdAt(Instant.now())
                .build();
        when(eventRepository.findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of(userEvent));

        var result = calendarService.getDensity(userId, LocalDate.of(2026, 8, 14));

        assertThat(result.calendarSynced()).isTrue();
        assertThat(result.blocks()).hasSize(2); // 종일 일정은 제외되고 2건만
        assertThat(result.blocks().get(0).source()).isEqualTo("user_event"); // 05시가 09시보다 먼저
        assertThat(result.blocks().get(1).source()).isEqualTo("google");
    }

    @Test
    void density_조회는_busy_전용_필드로_다음_페이지까지_수집한다() {
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = CalendarConnection.builder()
                .calendarConnectionId(UUID.randomUUID())
                .userId(userId)
                .provider("google")
                .externalAccountId("google-sub-1")
                .refreshTokenEnc(new byte[] {1, 2, 3})
                .connectedAt(Instant.now())
                .build();
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.of(connection));
        when(calendarTokenEncryptor.decrypt(any())).thenReturn("refresh-token".getBytes(StandardCharsets.UTF_8));
        when(responseSpec.body(GoogleTokenResponse.class))
                .thenReturn(new GoogleTokenResponse("access-token", null, null, 3600L));
        when(responseSpec.body(GoogleBusyEventsResponse.class)).thenReturn(
                new GoogleBusyEventsResponse(List.of(new GoogleBusyEvent(
                        new GoogleEventDateTime(Instant.parse("2026-08-14T09:00:00Z")),
                        new GoogleEventDateTime(Instant.parse("2026-08-14T10:00:00Z")))), "next+page=/opaque"),
                new GoogleBusyEventsResponse(List.of(new GoogleBusyEvent(
                        new GoogleEventDateTime(Instant.parse("2026-08-14T11:00:00Z")),
                        new GoogleEventDateTime(Instant.parse("2026-08-14T12:00:00Z")))), null));
        when(eventRepository.findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of());

        var result = calendarService.getDensity(userId, LocalDate.of(2026, 8, 14));

        assertThat(result.calendarSynced()).isTrue();
        assertThat(result.blocks()).extracting(block -> block.startsAt())
                .containsExactly(Instant.parse("2026-08-14T09:00:00Z"), Instant.parse("2026-08-14T11:00:00Z"));
        var uriCaptor = org.mockito.ArgumentCaptor.forClass(URI.class);
        verify(requestHeadersUriSpec, times(2)).uri(uriCaptor.capture());
        assertThat(uriCaptor.getAllValues()).allSatisfy(uri -> {
            var query = org.springframework.web.util.UriComponentsBuilder.fromUri(uri).build().getQueryParams();
            assertThat(decodedQueryParameter(query.getFirst("fields")))
                    .isEqualTo("items(start(dateTime),end(dateTime)),nextPageToken");
        });
        var nextPageQuery = org.springframework.web.util.UriComponentsBuilder.fromUri(uriCaptor.getAllValues().get(1))
                .build().getQueryParams();
        assertThat(decodedQueryParameter(nextPageQuery.getFirst("pageToken")))
                .isEqualTo("next+page=/opaque");
        assertThat(uriCaptor.getAllValues().get(1).getRawQuery())
                .contains("pageToken=next%2Bpage%3D%2Fopaque");
    }

    @Test
    void density_조회는_빈_nextPageToken에서_추가_요청_없이_정상_종료한다() {
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = connectedGoogleCalendar(userId);
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.of(connection));
        when(calendarTokenEncryptor.decrypt(any())).thenReturn("refresh-token".getBytes(StandardCharsets.UTF_8));
        when(responseSpec.body(GoogleTokenResponse.class))
                .thenReturn(new GoogleTokenResponse("access-token", null, null, 3600L));
        when(responseSpec.body(GoogleBusyEventsResponse.class))
                .thenReturn(new GoogleBusyEventsResponse(List.of(), ""))
                .thenThrow(new RestClientException("unexpected extra page"));
        when(eventRepository.findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of());

        var result = calendarService.getDensity(userId, LocalDate.of(2026, 8, 14));

        assertThat(result.calendarSynced()).isTrue();
        verify(requestHeadersUriSpec, times(1)).uri(any(URI.class));
    }

    @Test
    void density_조회는_다페이지_Google_HTTP를_가로지르는_transaction을_열지_않는다() throws Exception {
        var method = CalendarService.class.getMethod("getDensity", UUID.class, LocalDate.class);

        assertThat(org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                method, org.springframework.transaction.annotation.Transactional.class)).isNull();
    }

    @Test
    void density_조회는_반복_nextPageToken에서_추가_요청_없이_실패로_종료한다() {
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = connectedGoogleCalendar(userId);
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, "google")).thenReturn(Optional.of(connection));
        when(calendarTokenEncryptor.decrypt(any())).thenReturn("refresh-token".getBytes(StandardCharsets.UTF_8));
        when(responseSpec.body(GoogleTokenResponse.class))
                .thenReturn(new GoogleTokenResponse("access-token", null, null, 3600L));
        when(responseSpec.body(GoogleBusyEventsResponse.class))
                .thenReturn(new GoogleBusyEventsResponse(List.of(new GoogleBusyEvent(
                        new GoogleEventDateTime(Instant.parse("2026-08-14T09:00:00Z")),
                        new GoogleEventDateTime(Instant.parse("2026-08-14T10:00:00Z")))), "repeat"))
                .thenReturn(new GoogleBusyEventsResponse(List.of(), "repeat"))
                .thenThrow(new RestClientException("unexpected extra page"));
        when(eventRepository.findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any()))
                .thenReturn(List.of());

        var result = calendarService.getDensity(userId, LocalDate.of(2026, 8, 14));

        assertThat(result.calendarSynced()).isFalse();
        assertThat(result.blocks()).isEmpty();
        verify(requestHeadersUriSpec, times(2)).uri(any(URI.class));
    }

    private CalendarConnection connectedGoogleCalendar(UUID userId) {
        return CalendarConnection.builder()
                .calendarConnectionId(UUID.randomUUID())
                .userId(userId)
                .provider("google")
                .externalAccountId("google-sub-1")
                .refreshTokenEnc(new byte[] {1, 2, 3})
                .connectedAt(Instant.now())
                .build();
    }

    private String decodedQueryParameter(String value) {
        return value == null ? null : java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
