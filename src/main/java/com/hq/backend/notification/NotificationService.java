package com.hq.backend.notification;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 상태 입력에 따른 예약 알림 취소를 담당한다. FCM dispatch는 NotificationDispatchService가 담당한다. */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * 상태 입력(행동 기록) 시 해당 계획의 남은 예약 알림을 모두 취소한다.
     * TRD §8.1 "상태 입력 시 남은 슬롯 소각"
     *
     * @return 취소된 알림 수
     */
    @Transactional
    public int cancelPendingNotifications(UUID planId) {
        int cancelled = notificationRepository.cancelPendingByPlanId(planId);
        if (cancelled > 0) {
            log.info("[NotificationService] plan_id={} 예약 알림 {}건 취소", planId, cancelled);
        }
        return cancelled;
    }
}
