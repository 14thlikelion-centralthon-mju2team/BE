package com.hq.backend.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** dedup_key로 이미 존재하는 알림 확인 (조회용) */
    Optional<Notification> findByDedupKey(String dedupKey);

    /**
     * PostgreSQL unique dedup_key를 원자적으로 예약한다.
     * @return 1이면 신규 예약, 0이면 다른 요청이 이미 같은 키를 예약함
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO notification (
                plan_id, notification_category, notification_type, scheduled_at,
                delivery_status, body_masked, trigger_reason, dedup_key
            ) VALUES (
                :planId, :category, :type, :scheduledAt,
                'scheduled', :bodyMasked, :triggerReason, :dedupKey
            ) ON CONFLICT (dedup_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("planId") UUID planId,
            @Param("category") String category,
            @Param("type") String type,
            @Param("scheduledAt") Instant scheduledAt,
            @Param("bodyMasked") String bodyMasked,
            @Param("triggerReason") String triggerReason,
            @Param("dedupKey") String dedupKey);

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

    @Query("""
            SELECT COUNT(n) FROM Notification n
            WHERE n.planId IN :planIds
              AND n.notificationCategory = 'time'
              AND n.notificationType = 'critical'
              AND n.deliveryStatus IN ('sent', 'delivered')
              AND n.sentAt >= :startOfDay AND n.sentAt < :endOfDay
            """)
    int countSentCriticalByPlanIdInBetween(List<UUID> planIds, Instant startOfDay, Instant endOfDay);

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
