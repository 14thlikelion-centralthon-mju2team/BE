package com.hq.backend.notification;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 로컬 개발·테스트용 FCM 스텁. 실제 발송 대신 로그만 남긴다.
 * FirebaseApp 빈이 없을 때(firebase.service-account-path 미설정) 이 스텁이 사용됨.
 */
@Component
public class StubFcmSender implements FcmSender {

    private static final Logger log = LoggerFactory.getLogger(StubFcmSender.class);

    @Override
    public int send(List<String> tokens, String title, String body, String collapseKey, Map<String, String> data) {
        log.info("[FCM-STUB] 발송 시뮬레이션 — tokens={}, title='{}', body='{}', collapseKey='{}'",
                tokens.size(), title, body, collapseKey);
        return tokens.size();
    }
}
