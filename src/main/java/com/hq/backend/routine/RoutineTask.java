package com.hq.backend.routine;

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

// action_id는 "현재" 난이도다 — V1__init.sql 주석대로 adjustments 트리거만 이 값을 바꾼다.
// 이 엔티티/서비스에서 직접 UPDATE routine_tasks SET action_id를 하면 트리거가 없는
// 변경 경로가 생겨서 난이도 변경 이력(adjustments)이 비게 된다. 절대 여기서 바꾸지 말 것 —
// 난이도 변경은 feat/adjustments(Phase 4)에서 adjustments INSERT로만 한다.
@Entity
@Table(name = "routine_tasks")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutineTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID routineId;

    @Column(nullable = false)
    private UUID actionId;

    @Column(nullable = false)
    private int orderNo;

    private Instant archivedAt;

    @Column(nullable = false)
    private Instant createdAt;
}
