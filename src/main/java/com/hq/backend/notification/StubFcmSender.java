package com.hq.backend.notification;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 로컬 개발·테스트용 FCM 스텁. 실제 발송 대신 로그만 남긴다.
 * Firebase Admin SDK 구현체가 준비되면 @ConditionalOnProperty로 교체.
 */
@Component
public class StubFcmSender implements FcmSender {

    private static final Logger log = LoggerFactory.getLogger(StubFcmSender.class);

    @Override
    public int send(List<String> tokens, String title, String body, String collapseKey, Map<String, String> data) {
        log.info("[FCM-STUB] 발송 시뮬레이션 — tokens={}, title='{}', body='{}', collapseKey='{}', data={}",
                tokens.size(), title, body, collapseKey, data);
        return tokens.size(); // 스텁: 항상 전부 성공으로 간주
    }
}
