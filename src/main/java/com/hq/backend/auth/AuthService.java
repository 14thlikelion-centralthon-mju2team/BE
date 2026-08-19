package com.hq.backend.auth;

import com.hq.backend.auth.dto.GoogleLoginRequest;
import com.hq.backend.auth.dto.GoogleUserInfoResponse;
import com.hq.backend.auth.dto.LoginRequest;
import com.hq.backend.auth.dto.SignupRequest;
import com.hq.backend.auth.dto.SignupResponse;
import com.hq.backend.auth.dto.TokenResponse;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.pushdevice.PushDeviceRepository;
import com.hq.backend.user.User;
import com.hq.backend.user.UserCredential;
import com.hq.backend.user.UserCredentialRepository;
import com.hq.backend.user.UserIdentity;
import com.hq.backend.user.UserIdentityRepository;
import com.hq.backend.user.UserRepository;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

// ERD v3 전환(chore/be-schema-core, #61)에 맞춰 provider/providerUid/passwordHash가
// USER_IDENTITY/USER_CREDENTIAL로 옮겨간 것을 반영한 최소 수정. 로직(구글 토큰 검증,
// JWT 발급, 이메일 인증)은 기존 그대로이고 데이터 접근 경로만 새 테이블로 바꿨다.
// 만 14세 확인(age_confirmed_at)은 Ensom 범위에 없어 뺐다 — TODO(박찬): 이메일 인증
// 토큰 발급(AUTH_TOKEN), USER_IDENTITY 기반 계정 연결 정책 등 실제 Ensom 인증 플로우는
// 아직 반영 안 됨.
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final short LOGIN_FAIL_LOCK_THRESHOLD = 5;
    private static final long LOGIN_LOCK_MINUTES = 15;

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PushDeviceRepository pushDeviceRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RestClient restClient;
    private final TransactionTemplate transactionTemplate;

    @Value("${oauth.google.token-info-url}")
    private String googleTokenInfoUrl;

    @Value("${oauth.google.client-id}")
    private String googleClientId;

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "이미 가입된 이메일입니다.");
        }

        Instant now = Instant.now();
        User user = userRepository.save(User.builder()
                .email(request.email())
                .nickname(defaultNickname(request.email()))
                .timezone("Asia/Seoul")
                .createdAt(now)
                .accountStatus("active")
                .build());

        userIdentityRepository.save(UserIdentity.builder()
                .userId(user.getUserId())
                .provider("email")
                .providerUid(request.email())
                .linkedAt(now)
                .build());

        userCredentialRepository.save(UserCredential.builder()
                .userId(user.getUserId())
                .passwordHash(passwordEncoder.encode(request.password()))
                .passwordAlgo("argon2id")
                .passwordUpdatedAt(now)
                .failedAttempts((short) 0)
                .build());

        return new SignupResponse(user.getUserId(), user.getEmail(), "email");
    }

    // users.nickname은 not null이지만 가입 요청에 닉네임 입력을 받지 않으므로 임시값을 채운다.
    // 닉네임 설정 기능이 생기면 그때 덮어쓰면 된다.
    private String defaultNickname(String email) {
        return email.substring(0, email.indexOf('@'));
    }

    // TRD §10.2·부록A: 연속 5회 실패 시 15분 잠금. IP 단위 제한은 아직 없다(계정 단위만).
    // noRollbackFor: 실패 응답(ApiException)을 던지더라도 failedAttempts/lockedUntil 증가는
    // 커밋돼야 한다 — 기본 롤백 규칙대로면 실패를 보고하는 예외가 그 실패 카운트 자체를 지운다.
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."));

        UserCredential credential = userCredentialRepository.findById(user.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."));

        if (credential.getLockedUntil() != null && credential.getLockedUntil().isAfter(Instant.now())) {
            long retryAfterSec = Duration.between(Instant.now(), credential.getLockedUntil()).getSeconds();
            throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                    "연속 로그인 실패로 계정이 잠겼습니다. " + retryAfterSec + "초 후 다시 시도해주세요.");
        }

        if (!passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
            registerFailedAttempt(credential);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        if (credential.getFailedAttempts() > 0) {
            credential.setFailedAttempts((short) 0);
        }

        return issueTokens(user);
    }

    private void registerFailedAttempt(UserCredential credential) {
        short attempts = (short) (credential.getFailedAttempts() + 1);
        credential.setFailedAttempts(attempts);
        if (attempts >= LOGIN_FAIL_LOCK_THRESHOLD) {
            credential.setLockedUntil(Instant.now().plus(LOGIN_LOCK_MINUTES, ChronoUnit.MINUTES));
        }
    }

    // 구글 토큰 검증(외부 호출)을 트랜잭션 밖에서 먼저 끝내고, DB 쓰기가 필요한 신규 유저
    // 생성만 createGoogleUser() 안에서 TransactionTemplate으로 짧게 감싼다 — 네트워크
    // 호출 동안 DB 커넥션을 붙잡지 않기 위해서(FCM 전송을 트랜잭션 밖에 두는 것과 같은 원칙).
    public TokenResponse loginWithGoogle(GoogleLoginRequest request) {
        // id_token을 문자열로 이어붙이면 {}가 든 값이 URI 템플릿 변수로 해석돼 500이 난다.
        // encode()로 쿼리 파라미터를 인코딩한 URI를 넘겨 템플릿 확장을 우회한다.
        URI uri = UriComponentsBuilder.fromUriString(googleTokenInfoUrl)
                .queryParam("id_token", request.idToken())
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
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN", "구글 토큰이 유효하지 않습니다.");
        }

        if (info == null || !googleClientId.equals(info.aud())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN", "유효하지 않은 구글 토큰입니다.");
        }

        User user = userIdentityRepository.findByProviderAndProviderUid("google", info.sub())
                .map(identity -> userRepository.findById(identity.getUserId())
                        .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "USER_NOT_FOUND", "계정을 찾을 수 없습니다.")))
                .orElseGet(() -> createGoogleUser(info));

        return issueTokens(user);
    }

    private User createGoogleUser(GoogleUserInfoResponse info) {
        try {
            return transactionTemplate.execute(status -> {
                Instant now = Instant.now();
                User user = userRepository.save(User.builder()
                        .email(info.email())
                        .nickname(defaultNickname(info.email()))
                        .timezone("Asia/Seoul")
                        .createdAt(now)
                        .accountStatus("active")
                        .build());

                userIdentityRepository.save(UserIdentity.builder()
                        .userId(user.getUserId())
                        .provider("google")
                        .providerUid(info.sub())
                        .linkedAt(now)
                        .build());

                return user;
            });
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "이미 다른 방식으로 가입된 이메일입니다.");
        }
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user.getUserId());
        String refreshToken = jwtService.generateRefreshToken(user.getUserId());

        // refresh 토큰 해시를 DB에 저장 (회전·폐기 지원)
        String tokenHash = hashToken(refreshToken);
        Instant expiresAt = Instant.now().plusMillis(jwtService.getRefreshTokenExpirationMs());
        refreshTokenRepository.save(RefreshToken.create(user.getUserId(), tokenHash, expiresAt));

        return new TokenResponse(accessToken, refreshToken, jwtService.getAccessTokenExpirationSeconds());
    }

    /**
     * Refresh 토큰으로 새 토큰 쌍 발급 (토큰 회전).
     * 조건부 UPDATE로 동시 요청 시 하나만 성공하도록 보장한다.
     */
    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        UUID userId = jwtService.getUserIdFromRefreshToken(rawRefreshToken);

        String tokenHash = hashToken(rawRefreshToken);

        // 원자적 소비: revoked_at IS NULL AND expires_at > now() 인 경우에만 revoke
        int consumed = refreshTokenRepository.revokeByTokenHash(tokenHash);
        if (consumed == 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "만료되었거나 이미 사용된 토큰입니다.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "사용자를 찾을 수 없습니다."));

        return issueTokens(user);
    }

    /**
     * 로그아웃 — 해당 사용자의 모든 활성 refresh 토큰 폐기 + push device 비활성화.
     */
    @Transactional
    public void logout(UUID userId) {
        refreshTokenRepository.revokeAllByUserId(userId);
        pushDeviceRepository.revokeAllByUserId(userId);
    }

    private String hashToken(String token) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
