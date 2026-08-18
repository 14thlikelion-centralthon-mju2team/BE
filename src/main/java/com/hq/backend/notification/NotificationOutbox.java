package com.hq.backend.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * TRD §13.1 아웃박스 패턴.
 * notification INSERT가 커밋된 후 FCM 발송을 보장하기 위한 아웃박스 행.
 * 워커가 주기적으로 폴링해서 발송하고, 성공 시 processedAt을 기록한다.
 * 실패 시 retryCount 증가, 3회 초과 시 포기(notification.delivery_status → failed).
 */
@Entity
@Table(name = "notification_outbox")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID outboxId;

    @Column(nullable = false)
    private UUID notificationId;

    /** FCM 발송 대상 사용자 ID (push_device 조회용) */
    @Column(nullable = false)
    private UUID userId;

    /** FCM collapse_key: event_id:slot — 트레이에 최신 1건만 유지 */
    @Column(nullable = false)
    private String collapseKey;

    /** 발송할 알림 본문 (bodyMasked와 동일하거나 잠금 해제 시 원문) */
    @Column(nullable = false)
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    @Setter
    private Instant processedAt;

    @Setter
    @Column(nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Setter
    @Column(nullable = false)
    @Builder.Default
    private String status = "pending"; // pending | processing | done | dead

    public void incrementRetry() {
        this.retryCount++;
        if (this.retryCount >= 3) {
            this.status = "dead";
        }
    }
}
