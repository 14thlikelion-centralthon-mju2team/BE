package com.hq.backend.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, UUID> {

    /** 발송 대기 중인 아웃박스 행 조회 (워커용) */
    @Query("""
            SELECT o FROM NotificationOutbox o
            WHERE o.status = 'pending'
            ORDER BY o.createdAt
            """)
    List<NotificationOutbox> findPendingOrderByCreatedAt();

    /** 특정 알림의 아웃박스 행 조회 (취소 시 사용) */
    List<NotificationOutbox> findByNotificationIdAndStatus(UUID notificationId, String status);
}
