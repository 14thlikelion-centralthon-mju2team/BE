package com.hq.backend.calendar;

import com.hq.backend.calendar.dto.BusyBlockResponse;
import com.hq.backend.calendar.dto.CalendarConnectionResponse;
import com.hq.backend.calendar.dto.ConnectCalendarRequest;
import com.hq.backend.calendar.dto.DensityResponse;
import com.hq.backend.calendar.dto.GoogleCalendarEventsResponse;
import com.hq.backend.calendar.dto.GoogleTokenResponse;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.UserEventRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class CalendarService {

    private static final String PROVIDER_GOOGLE = "google";
    private static final String SOURCE_GOOGLE = "google";
    private static final String SOURCE_USER_EVENT = "user_event";
    // TRD 6장 — 팀 전체가 아직 사용자별 타임존을 다루지 않는다(User.timezone은 저장만 하고 안 씀).
    // 날짜 경계 계산도 같은 수준으로 단순화: Asia/Seoul 고정.
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    private final CalendarConnectionRepository calendarConnectionRepository;
    private final UserEventRepository userEventRepository;
    private final BytesEncryptor calendarTokenEncryptor;
    private final RestClient restClient;

    @Value("${oauth.google.token-url}")
    private String googleTokenUrl;

    @Value("${oauth.google.client-id}")
    private String googleClientId;

    @Value("${oauth.google.client-secret}")
    private String googleClientSecret;

    @Value("${oauth.google.calendar-events-url}")
    private String googleCalendarEventsUrl;

    @Transactional
    public CalendarConnectionResponse connect(UUID userId, ConnectCalendarRequest request) {
        GoogleTokenResponse tokenResponse = exchangeAuthCode(request.authCode());
        Optional<CalendarConnection> existing =
                calendarConnectionRepository.findByUserIdAndProvider(userId, PROVIDER_GOOGLE);
        Instant now = Instant.now();

        // 이미 동의한 앱에 대한 재교환이면 구글이 refresh_token을 안 줄 수 있다 — 기존 연결이
        // 있으면 그 토큰을 그대로 쓰고 scope·connectedAt만 갱신, 없으면 재동의를 요구한다.
        if (tokenResponse.refreshToken() == null) {
            CalendarConnection connection = existing.orElseThrow(() -> new ApiException(
                    HttpStatus.BAD_REQUEST, "REFRESH_TOKEN_MISSING",
                    "구글이 refresh_token을 반환하지 않았고 기존 연결도 없습니다. 동의 화면을 다시 띄워주세요."));
            connection.setScope(tokenResponse.scope());
            connection.setConnectedAt(now);
            connection.setRevokedAt(null);
            return toResponse(connection);
        }

        byte[] encrypted =
                calendarTokenEncryptor.encrypt(tokenResponse.refreshToken().getBytes(StandardCharsets.UTF_8));

        CalendarConnection connection = existing.orElseGet(() -> CalendarConnection.builder()
                .userId(userId)
                .provider(PROVIDER_GOOGLE)
                .build());
        connection.setRefreshTokenEnc(encrypted);
        connection.setScope(tokenResponse.scope());
        connection.setConnectedAt(now);
        connection.setRevokedAt(null);

        if (existing.isEmpty()) {
            calendarConnectionRepository.save(connection);
        }

        return toResponse(connection);
    }

    @Transactional
    public void disconnect(UUID userId) {
        CalendarConnection connection = calendarConnectionRepository
                .findByUserIdAndProvider(userId, PROVIDER_GOOGLE)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "CALENDAR_NOT_CONNECTED", "연결된 구글 캘린더가 없습니다."));
        connection.setRevokedAt(Instant.now());
    }

    @Transactional(readOnly = true)
    public DensityResponse getDensity(UUID userId, LocalDate date) {
        Instant rangeStart = date.atStartOfDay(DEFAULT_ZONE).toInstant();
        Instant rangeEnd = date.plusDays(1).atStartOfDay(DEFAULT_ZONE).toInstant();

        GoogleFetchResult googleResult = fetchGoogleBusyBlocks(userId, rangeStart, rangeEnd);

        List<BusyBlockResponse> blocks = new ArrayList<>();
        blocks.addAll(googleResult.blocks());
        blocks.addAll(fetchUserEventBusyBlocks(userId, rangeStart, rangeEnd));
        blocks.sort(Comparator.comparing(BusyBlockResponse::startsAt));
        return new DensityResponse(googleResult.synced(), blocks);
    }

    private List<BusyBlockResponse> fetchUserEventBusyBlocks(UUID userId, Instant rangeStart, Instant rangeEnd) {
        return userEventRepository
                .findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(userId, rangeEnd, rangeStart)
                .stream()
                .map(e -> new BusyBlockResponse(e.getStartsAt(), e.getEndsAt(), SOURCE_USER_EVENT))
                .toList();
    }

    // 연결이 없거나·해제됐거나·구글 API 호출이 실패해도 전체 요청은 안 죽는다 — 빈 목록으로
    // 넘어간다. TRD 4장 "권한 상태 3단계 전부 안 끊긴다" 원칙과 같은 이유: 캘린더 연동은
    // user_events 기반 밀도 계산에 곁들이는 부가 신호일 뿐, 이게 실패했다고 API 전체가
    // 500을 낼 이유는 없다.
    //
    // 다만 "앱이 안 죽는다"와 "결과가 항상 맞다"는 다른 얘기다(#29) — 연동 자체가 없는
    // 사용자(할 게 없었을 뿐)와 연동은 돼있는데 이번 호출에서 실패한 사용자를 구분해야
    // 클라이언트가 "오늘 일정이 없다"와 "동기화가 끊겼다"를 구분해서 보여줄 수 있다.
    // 그래서 synced 플래그를 별도로 리턴한다 — 연결 없음은 synced=true(실패가 아님),
    // 연결은 있는데 토큰 갱신·API 호출이 실패한 경우만 synced=false.
    private GoogleFetchResult fetchGoogleBusyBlocks(UUID userId, Instant rangeStart, Instant rangeEnd) {
        Optional<CalendarConnection> connection =
                calendarConnectionRepository.findByUserIdAndProvider(userId, PROVIDER_GOOGLE);
        if (connection.isEmpty() || connection.get().getRevokedAt() != null) {
            return new GoogleFetchResult(true, List.of());
        }

        try {
            byte[] decrypted = calendarTokenEncryptor.decrypt(connection.get().getRefreshTokenEnc());
            Optional<String> accessToken = refreshAccessToken(new String(decrypted, StandardCharsets.UTF_8));
            if (accessToken.isEmpty()) {
                return new GoogleFetchResult(false, List.of());
            }

            URI uri = UriComponentsBuilder.fromUriString(googleCalendarEventsUrl)
                    .queryParam("timeMin", rangeStart.toString())
                    .queryParam("timeMax", rangeEnd.toString())
                    .queryParam("singleEvents", true)
                    .queryParam("orderBy", "startTime")
                    .encode()
                    .build()
                    .toUri();

            GoogleCalendarEventsResponse response = restClient.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + accessToken.get())
                    .retrieve()
                    .body(GoogleCalendarEventsResponse.class);

            if (response == null || response.items() == null) {
                return new GoogleFetchResult(false, List.of());
            }

            // ponytail: 종일 일정(date만 있고 dateTime 없음)은 건너뛴다 — GoogleEventDateTime 참고.
            List<BusyBlockResponse> blocks = response.items().stream()
                    .filter(item -> item.start() != null && item.start().dateTime() != null
                            && item.end() != null && item.end().dateTime() != null)
                    .map(item -> new BusyBlockResponse(item.start().dateTime(), item.end().dateTime(), SOURCE_GOOGLE))
                    .toList();
            return new GoogleFetchResult(true, blocks);
        } catch (RestClientException | IllegalStateException e) {
            // IllegalStateException은 BytesEncryptor.decrypt() 실패(암호화 키 로테이션, 저장된
            // 값 손상 등)일 때 던져진다 — RestClientException과 별개 원인이라 같이 잡아야 한다.
            return new GoogleFetchResult(false, List.of());
        }
    }

    private record GoogleFetchResult(boolean synced, List<BusyBlockResponse> blocks) {
    }

    private Optional<String> refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        form.add("client_id", googleClientId);
        form.add("client_secret", googleClientSecret);
        form.add("grant_type", "refresh_token");

        try {
            GoogleTokenResponse response = postTokenForm(form);
            return response == null ? Optional.empty() : Optional.ofNullable(response.accessToken());
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }

    private GoogleTokenResponse exchangeAuthCode(String authCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", authCode);
        form.add("client_id", googleClientId);
        form.add("client_secret", googleClientSecret);
        form.add("redirect_uri", ""); // serverAuthCode 플로우(모바일)는 빈 문자열
        form.add("grant_type", "authorization_code");

        GoogleTokenResponse response;
        try {
            response = postTokenForm(form);
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CALENDAR_CONNECT_FAILED", "구글과 토큰 교환에 실패했습니다.");
        }

        if (response == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CALENDAR_CONNECT_FAILED", "구글과 토큰 교환에 실패했습니다.");
        }
        return response;
    }

    private GoogleTokenResponse postTokenForm(MultiValueMap<String, String> form) {
        return restClient.post()
                .uri(googleTokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(GoogleTokenResponse.class);
    }

    private CalendarConnectionResponse toResponse(CalendarConnection connection) {
        return new CalendarConnectionResponse(connection.getProvider(), connection.getScope(), connection.getConnectedAt());
    }
}
