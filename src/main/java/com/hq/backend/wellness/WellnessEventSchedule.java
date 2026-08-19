package com.hq.backend.wellness;

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

// 웰니스 이벤트 푸시(선크림 재도포 등)의 예약·응답 저장소. 발사 게이트(6중 게이트, TR-11)와
// 백오프 로직은 orchestrator(박찬) 담당 — 이 엔티티는 영속화만. intervalMinutesSnapshot은
// 발사 시점 사용자 설정을 복사한 사후 분석용 스냅샷.
@Entity
@Table(name = "wellness_event_schedule")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WellnessEventSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID wellnessEventId;

    @Column(nullable = false)
    private UUID planId;

    @Setter
    private UUID notificationId;

    @Column(nullable = false)
    private String actionCode; // M3 catalog: uv_reapply | pm_recheck | hydration_intake (plus persisted legacy codes)

    private Integer intervalMinutesSnapshot;

    @Column(nullable = false)
    private Instant scheduledAt;

    @Setter
    private Instant sentAt;

    @Setter
    private String responseAction; // completed | snoozed | stop_today | ignored

    @Setter
    private String userRating; // useful | not_relevant

    @Column(nullable = false)
    private short sequenceNo; // 일정당 회차, ERD 기본 1회 제한

    @Setter
    private Instant cancelledAt;

    @Setter
    private String cancelReason; // indoor | plan_changed | user_completed
}
