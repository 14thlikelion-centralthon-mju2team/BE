package com.hq.backend.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** dedup_key로 이미 존재하는 알림 확인 (중복 발송 차단) */
    Optional<Notification> findByDedupKey(String dedupKey);

    /** 발송 대기 중인 알림 조회 — 아웃박스 워커가 사용 */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.scheduledAt <= :now
              AND n.deliveryStatus IN ('scheduled', 'failed')
            ORDER BY n.scheduledAt
            """)
    List<Notification> findPendingNotifications(Instant now);

    /** 특정 plan의 아직 발송되지 않은 알림 목록 (상태 입력 시 취소 대상) */
    List<Notification> findByPlanIdAndDeliveryStatus(UUID planId, String deliveryStatus);

    /** 특정 plan의 시간 알림 개수 (예산 확인용) */
    @Query("""
            SELECT COUNT(n) FROM Notification n
            WHERE n.planId = :planId
              AND n.notificationCategory = 'time'
              AND n.deliveryStatus <> 'cancelled'
            """)
    int countTimeNotificationsByPlanId(UUID planId);

    /** 상태 입력 시 남은 예약 알림 일괄 취소 */
    @Modifying
    @Query("""
            UPDATE Notification n
            SET n.deliveryStatus = 'cancelled'
            WHERE n.planId = :planId
              AND n.deliveryStatus = 'scheduled'
            """)
    int cancelPendingByPlanId(UUID planId);
}
