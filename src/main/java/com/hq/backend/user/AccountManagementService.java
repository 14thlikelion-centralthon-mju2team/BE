package com.hq.backend.user;

import com.hq.backend.auth.RefreshToken;
import com.hq.backend.auth.RefreshTokenRepository;
import com.hq.backend.auth.dto.GoogleUserInfoResponse;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.EventActionLogRepository;
import com.hq.backend.event.EventRepository;
import com.hq.backend.user.dto.LinkProviderRequest;
import com.hq.backend.user.dto.ProviderResponse;
import com.hq.backend.user.dto.SessionResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class AccountManagementService {

    private final UserIdentityRepository userIdentityRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EventRepository eventRepository;
    private final EventActionLogRepository eventActionLogRepository;
    private final RestClient restClient;

    @Value("${oauth.google.token-info-url}")
    private String googleTokenInfoUrl;

    @Value("${oauth.google.client-id}")
    private String googleClientId;

    // ─── Providers ───

    @Transactional(readOnly = true)
    public List<ProviderResponse> listProviders(UUID userId) {
        return userIdentityRepository.findAllByUserIdAndRevokedAtIsNull(userId)
                .stream()
                .map(ProviderResponse::from)
                .toList();
    }

    @Transactional
    public ProviderResponse linkProvider(UUID userId, LinkProviderRequest request) {
        String provider = request.provider();
        String providerUid;

        if ("google".equalsIgnoreCase(provider)) {
            providerUid = verifyGoogleToken(request.providerToken());
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_PROVIDER",
                    "지원하지 않는 제공자입니다.");
        }

        // 이미 해당 provider로 연결된 active identity가 있는지 확인
        List<UserIdentity> activeIdentities = userIdentityRepository.findAllByUserIdAndRevokedAtIsNull(userId);
        boolean alreadyLinked = activeIdentities.stream()
                .anyMatch(id -> id.getProvider().equalsIgnoreCase(provider));
        if (alreadyLinked) {
            throw new ApiException(HttpStatus.CONFLICT, "PROVIDER_ALREADY_LINKED",
                    "이미 연결된 제공자입니다.");
        }

        // 다른 사용자가 같은 providerUid를 사용 중인지 확인
        userIdentityRepository.findByProviderAndProviderUid(provider.toLowerCase(), providerUid)
                .ifPresent(existing -> {
                    throw new ApiException(HttpStatus.CONFLICT, "PROVIDER_UID_IN_USE",
                            "해당 소셜 계정은 이미 다른 사용자에게 연결되어 있습니다.");
                });

        UserIdentity identity = UserIdentity.builder()
                .userId(userId)
                .provider(provider.toLowerCase())
                .providerUid(providerUid)
                .linkedAt(Instant.now())
                .build();
        identity = userIdentityRepository.save(identity);
        return ProviderResponse.from(identity);
    }

    @Transactional
    public void unlinkProvider(UUID userId, UUID identityId) {
        UserIdentity identity = userIdentityRepository.findById(identityId)
                .filter(id -> id.getUserId().equals(userId) && id.getRevokedAt() == null)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "IDENTITY_NOT_FOUND",
                        "연결 정보를 찾을 수 없습니다."));

        long activeCount = userIdentityRepository.findAllByUserIdAndRevokedAtIsNull(userId).size();
        if (activeCount <= 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LAST_IDENTITY",
                    "마지막 남은 인증 수단은 해제할 수 없습니다.");
        }

        identity.setRevokedAt(Instant.now());
    }

    // ─── Sessions ───

    @Transactional(readOnly = true)
    public List<SessionResponse> listSessions(UUID userId, String refreshToken) {
        String currentTokenHash = refreshToken != null ? hashToken(refreshToken) : null;
        List<RefreshToken> tokens = refreshTokenRepository
                .findAllByUserIdAndRevokedAtIsNullAndExpiresAtAfter(userId, Instant.now());
        return tokens.stream()
                .map(t -> SessionResponse.from(t,
                        currentTokenHash != null && t.getTokenHash().equals(currentTokenHash)))
                .toList();
    }

    @Transactional
    public void revokeSession(UUID userId, UUID refreshTokenId, String refreshToken) {
        RefreshToken token = refreshTokenRepository.findById(refreshTokenId)
                .filter(t -> t.getUserId().equals(userId) && !t.isRevoked())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND",
                        "세션을 찾을 수 없습니다."));

        String currentTokenHash = refreshToken != null ? hashToken(refreshToken) : null;
        if (currentTokenHash != null && token.getTokenHash().equals(currentTokenHash)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_REVOKE_CURRENT",
                    "현재 사용 중인 세션은 해제할 수 없습니다.");
        }

        token.revoke();
    }

    @Transactional
    public void revokeOtherSessions(UUID userId, String refreshToken) {
        String currentTokenHash = refreshToken != null ? hashToken(refreshToken) : "";
        refreshTokenRepository.revokeAllByUserIdExcept(userId, currentTokenHash);
    }

    // ─── Action Logs ───

    @Transactional
    public void deleteActionLogs(UUID userId) {
        List<UUID> eventIds = eventRepository.findAllEventIdsByUserId(userId);
        if (!eventIds.isEmpty()) {
            eventActionLogRepository.deleteAllByEventIdIn(eventIds);
        }
    }

    // ─── Private helpers ───

    private String verifyGoogleToken(String idToken) {
        URI uri = UriComponentsBuilder.fromUriString(googleTokenInfoUrl)
                .queryParam("id_token", idToken)
                .encode()
                .build()
                .toUri();

        GoogleUserInfoResponse info;
        try {
            info = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(GoogleUserInfoResponse.class);
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN",
                    "구글 토큰이 유효하지 않습니다.");
        }

        if (info == null || !googleClientId.equals(info.aud())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN",
                    "유효하지 않은 구글 토큰입니다.");
        }

        return info.sub();
    }

    private String hashToken(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
