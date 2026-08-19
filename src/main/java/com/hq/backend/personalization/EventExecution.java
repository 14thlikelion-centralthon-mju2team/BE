package com.hq.backend.personalization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// PK가 event.event_id를 공유하는 약한 엔티티 — 일정당 결과 1건. rushLoadScore는
// score_purpose=priority_only(운영 지표)이며 사용자 스트레스·정신건강 측정이 아니다(절대 원칙 3).
@Entity
@Table(name = "event_execution")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventExecution {

    @Id
    private UUID eventId;

    @Setter
    private UUID finalPlanId;

    @Setter
    private Instant actualPrepStartedAt;

    @Setter
    private Instant actualPrepFinishedAt;

    @Setter
    private Instant actualDepartedAt;

    @Setter
    private Instant actualArrivedAt;

    @Setter
    @Column(nullable = false)
    private String arrivalResult; // early | on_time | rushed | late | unknown

    @Setter
    @Column(nullable = false)
    private String resultSource; // user | geo | inferred

    @Setter
    private Integer actualOutdoorMinutes;

    @Setter
    private BigDecimal prepDelayNorm;

    @Setter
    private BigDecimal departDelayNorm;

    @Setter
    private BigDecimal criticalAlertNorm;

    @Setter
    private Short rushLoadScore;

    @Column(nullable = false)
    private Instant createdAt;

    @Setter
    @Column(nullable = false)
    private Instant updatedAt;
}
