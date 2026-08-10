package com.hq.backend.auth;

import com.hq.backend.auth.dto.LoginRequest;
import com.hq.backend.auth.dto.SignupRequest;
import com.hq.backend.auth.dto.SignupResponse;
import com.hq.backend.auth.dto.TokenResponse;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MIN_AGE = 14;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

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

        return new TokenResponse(
                jwtService.generateAccessToken(user.getId()),
                jwtService.generateRefreshToken(user.getId()),
                jwtService.getAccessTokenExpirationSeconds());
    }
}
