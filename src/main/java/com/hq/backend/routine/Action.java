package com.hq.backend.routine;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// V1__init.sql의 actions 라이브러리(hydration.water 등)를 그대로 매핑.
// AI 서버는 이 표를 SELECT 전용 DB 롤로 직접 읽는다(Phase 6, setting/security) —
// 그래서 이 엔티티/Repository에는 공개 REST 엔드포인트를 붙이지 않는다.
// RoutineService가 routine_tasks 생성 시 action_id 허용목록 검증에만 사용한다.
@Entity
@Table(name = "actions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Action {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String category;
    private String ladderKey;
    private int difficulty;
    private int estMinutes;
    private String title;
    private String description;
}
