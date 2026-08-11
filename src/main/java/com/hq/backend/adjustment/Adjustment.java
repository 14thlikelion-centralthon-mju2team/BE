package com.hq.backend.adjustment;

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

// V1__init.sql: adjustments는 append-only 로그다 — 실제 난이도 변경(routine_tasks.action_id
// 갱신)은 trg_apply_adjustment 트리거가 INSERT 시점에 적용한다. 그래서 이 엔티티에도 update용
// setter가 없다(UserConsent와 같은 이유).
@Entity
@Table(name = "adjustments")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Adjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID routineTaskId;

    @Column(nullable = false)
    private UUID beforeActionId;

    @Column(nullable = false)
    private UUID afterActionId;

    @Column(nullable = false)
    private String triggerType;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;
}
