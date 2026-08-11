package com.hq.backend.calendar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.encrypt.Encryptors;

@Configuration
public class EncryptionConfig {

    @Bean
    public BytesEncryptor calendarTokenEncryptor(
            @Value("${app.encryption.secret}") String secret, @Value("${app.encryption.salt}") String salt) {
        return Encryptors.stronger(secret, salt);
    }
}
