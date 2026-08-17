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

// 외출 전 제안 행동. displayRank 1~3(ck_wellness_rank가 DB에서 강제). reasonSnapshot은
// 설명가능성(PRD §8.5)의 근거 문장 — 렌더된 텍스트를 그대로 보존한다.
@Entity
@Table(name = "plan_wellness_action")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanWellnessAction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID wellnessActionId;

    @Column(nullable = false)
    private UUID planId;

    @Column(nullable = false)
    private String wellnessTopic; // uv | pm | temp | rain | hydration

    @Column(nullable = false)
    private String actionCode; // sunscreen | mask | hydration | outerwear | umbrella

    @Column(nullable = false)
    private String actionLabel;

    @Column(nullable = false)
    private short displayRank; // 1~3

    @Column(nullable = false)
    private String reasonSnapshot;

    @Setter
    @Column(nullable = false)
    private String completionStatus; // proposed | completed | dismissed

    @Setter
    private Instant respondedAt;
}
