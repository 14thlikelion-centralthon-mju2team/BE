package com.hq.backend.checkin;

import org.springframework.stereotype.Component;

// FR-003 컨디션 추론 — 규칙 기반 분기만 사용한다 (D-005, 외부 LLM 호출 절대 금지).
// ponytail: 지금 시점에 서버가 가진 신호는 그날 입력한 available_minutes뿐이다
// (task_logs·routine_runs 이력이 아직 없음 — FR-005 완성 후 최근 수행 패턴을 반영하도록 확장).
// 임계값은 임시 추정치이고, 실사용 데이터가 쌓이면 조정 대상이다.
@Component
public class ConditionRuleEngine {

    private static final int TIRED_THRESHOLD_MINUTES = 15;
    private static final int GOOD_THRESHOLD_MINUTES = 45;

    public Condition infer(Integer availableMinutes) {
        if (availableMinutes == null) {
            return Condition.NORMAL;
        }
        if (availableMinutes < TIRED_THRESHOLD_MINUTES) {
            return Condition.TIRED;
        }
        if (availableMinutes < GOOD_THRESHOLD_MINUTES) {
            return Condition.NORMAL;
        }
        return Condition.GOOD;
    }
}
