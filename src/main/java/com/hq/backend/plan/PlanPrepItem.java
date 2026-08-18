package com.hq.backend.plan;

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

// PlanEngine 응답의 checklist를 그대로 스냅샷한다(TRD §9.2 PLAN-05 병합 결과).
// event_prep_item_id는 지금 채우지 않는다 — EVENT_PREP_ITEM 투영은 별도 후속 작업.
@Entity
@Table(name = "plan_prep_item")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanPrepItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID planPrepItemId;

    @Column(nullable = false)
    private UUID planId;

    private UUID prepRuleId;

    private UUID eventPrepItemId;

    @Column(nullable = false)
    private String itemNameSnapshot;

    @Column(nullable = false)
    private String actionTypeSnapshot;

    @Column(nullable = false)
    private int appliedMinutes;

    @Column(nullable = false)
    private boolean isSensitive;

    @Column(nullable = false)
    private String sourceType; // rule | event_item | weather

    private String reasonSnapshot;

    @Setter
    @Column(nullable = false)
    private String completionStatus; // pending | completed

    @Setter
    private Instant completedAt;
}
