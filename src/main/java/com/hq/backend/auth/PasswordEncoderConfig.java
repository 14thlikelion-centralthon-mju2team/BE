package com.hq.backend.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// TRD §3 스택 표 — 비밀번호 해시는 Argon2id로 확정(user_credential.password_algo CHECK
// 제약이 'argon2id' 고정값만 허용한다). 기존 BCryptPasswordEncoder에서 교체.
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
