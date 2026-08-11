package com.hq.backend.auth;

import com.hq.backend.auth.dto.GoogleLoginRequest;
import com.hq.backend.auth.dto.GoogleUserInfoResponse;
import com.hq.backend.auth.dto.LoginRequest;
import com.hq.backend.auth.dto.SignupRequest;
import com.hq.backend.auth.dto.SignupResponse;
import com.hq.backend.auth.dto.TokenResponse;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MIN_AGE = 14;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RestClient restClient;

    @Value("${oauth.google.token-info-url}")
    private String googleTokenInfoUrl;

    @Value("${oauth.google.client-id}")
    private String googleClientId;

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "이미 가입된 이메일입니다.");
        }
        if (Period.between(request.birthDate(), LocalDate.now()).getYears() < MIN_AGE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "UNDER_AGE", "만 14세 이상만 가입할 수 있습니다.");
        }

        User user = userRepository.save(User.builder()
                .provider("email")
                .providerUid(request.email())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .nickname(defaultNickname(request.email()))
                .timezone("Asia/Seoul")
                .ageConfirmedAt(Instant.now())
                .createdAt(Instant.now())
                .build());

        return new SignupResponse(user.getId(), user.getEmail(), user.getProvider(), true);
    }

    // users.nickname은 not null이지만 가입 요청에 닉네임 입력을 받지 않으므로 임시값을 채운다.
    // 닉네임 설정 기능이 생기면 그때 덮어쓰면 된다.
    private String defaultNickname(String email) {
        return email.substring(0, email.indexOf('@'));
    }

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .filter(u -> u.getPasswordHash() != null && passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."));

        return issueTokens(user);
    }

    @Transactional
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

        User user = userRepository.findByProviderAndProviderUid("google", info.sub())
                .orElseGet(() -> createGoogleUser(info));

        return issueTokens(user);
    }

    private User createGoogleUser(GoogleUserInfoResponse info) {
        try {
            return userRepository.save(User.builder()
                    .provider("google")
                    .providerUid(info.sub())
                    .email(info.email())
                    .nickname(defaultNickname(info.email()))
                    .timezone("Asia/Seoul")
                    .createdAt(Instant.now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "이미 다른 방식으로 가입된 이메일입니다.");
        }
    }

    private TokenResponse issueTokens(User user) {
        return new TokenResponse(
                jwtService.generateAccessToken(user.getId()),
                jwtService.generateRefreshToken(user.getId()),
                jwtService.getAccessTokenExpirationSeconds());
    }
}
