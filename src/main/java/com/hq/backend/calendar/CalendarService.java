package com.hq.backend.calendar;

import com.hq.backend.calendar.dto.CalendarConnectionResponse;
import com.hq.backend.calendar.dto.ConnectCalendarRequest;
import com.hq.backend.calendar.dto.GoogleTokenResponse;
import com.hq.backend.common.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.springframework.web.client.RestClientResponseException;

@Service
@RequiredArgsConstructor
public class CalendarService {

    private static final String PROVIDER_GOOGLE = "google";

    private final CalendarConnectionRepository calendarConnectionRepository;
    private final BytesEncryptor calendarTokenEncryptor;
    private final RestClient restClient;

    @Value("${oauth.google.token-url}")
    private String googleTokenUrl;

    @Value("${oauth.google.client-id}")
    private String googleClientId;

    @Value("${oauth.google.client-secret}")
    private String googleClientSecret;

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

    private GoogleTokenResponse exchangeAuthCode(String authCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", authCode);
        form.add("client_id", googleClientId);
        form.add("client_secret", googleClientSecret);
        form.add("redirect_uri", ""); // serverAuthCode 플로우(모바일)는 빈 문자열
        form.add("grant_type", "authorization_code");

        GoogleTokenResponse response;
        try {
            response = restClient.post()
                    .uri(googleTokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(GoogleTokenResponse.class);
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CALENDAR_CONNECT_FAILED", "구글과 토큰 교환에 실패했습니다.");
        }

        if (response == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CALENDAR_CONNECT_FAILED", "구글과 토큰 교환에 실패했습니다.");
        }
        return response;
    }

    private CalendarConnectionResponse toResponse(CalendarConnection connection) {
        return new CalendarConnectionResponse(connection.getProvider(), connection.getScope(), connection.getConnectedAt());
    }
}
