package com.hq.backend.notification;

import java.util.List;
import java.util.Map;

/**
 * FCM 발송 추상화. 실제 Firebase Admin SDK 연동은 FirebaseFcmSender가 담당.
 * 로컬·테스트에서는 StubFcmSender가 로그만 찍는다.
 */
public interface FcmSender {

    /**
     * FCM 메시지를 발송한다.
     *
     * @param tokens      대상 기기의 FCM 토큰 목록
     * @param title       알림 제목
     * @param body        알림 본문 (잠금화면용 마스킹 문구)
     * @param collapseKey 트레이에 최신 1건만 유지하기 위한 키
     * @param data        추가 데이터 페이로드 (클라이언트 라우팅용)
     * @return 발송 성공한 토큰 수
     */
    int send(List<String> tokens, String title, String body, String collapseKey, Map<String, String> data);
}
