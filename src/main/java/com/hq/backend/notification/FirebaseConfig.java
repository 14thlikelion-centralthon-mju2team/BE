package com.hq.backend.notification;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.io.FileInputStream;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Firebase Admin SDK 초기화.
 * firebase.service-account-path가 실제 값으로 설정되어 있을 때만 활성화.
 * 빈 문자열이면 비활성화 — StubFcmSender가 사용된다.
 */
@Configuration
@ConditionalOnExpression("!'${firebase.service-account-path:}'.isBlank()")
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${firebase.service-account-path}")
    private String serviceAccountPath;

    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        if (FirebaseApp.getApps().isEmpty()) {
            FileInputStream serviceAccount = new FileInputStream(serviceAccountPath);
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();
            FirebaseApp app = FirebaseApp.initializeApp(options);
            log.info("[Firebase] 초기화 완료: project={}", app.getOptions().getProjectId());
            return app;
        }
        return FirebaseApp.getInstance();
    }
}
