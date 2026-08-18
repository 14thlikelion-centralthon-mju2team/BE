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
 * TRD §8 · V6 DDL notification 테이블 매핑.
 * dedup_key UNIQUE로 중복 발송을 구조적으로 차단한다.
 * delivery_status: scheduled → sent/delivered/failed/cancelled
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID notificationId;

    @Column(nullable = false)
    private UUID planId;

    /** time | wellness */
    @Column(nullable = false)
    private String notificationCategory;

    /** relaxed | critical | disruption | wellness_event */
    @Column(nullable = false)
    private String notificationType;

    @Column(nullable = false)
    private Instant scheduledAt;

    @Setter
    private Instant sentAt;

    /** scheduled | sent | delivered | failed | cancelled */
    @Setter
    @Column(nullable = false)
    private String deliveryStatus;

    /** 잠금화면용 일반화 문구 (민감 항목 마스킹) */
    @Column(nullable = false)
    private String bodyMasked;

    /** 알림 로그 표시용 사유 */
    @Column(nullable = false)
    private String triggerReason;

    /** sha1(event_id:slot:revision_no) — UNIQUE 제약으로 중복 발송 차단 */
    @Column(nullable = false, unique = true)
    private String dedupKey;
}
