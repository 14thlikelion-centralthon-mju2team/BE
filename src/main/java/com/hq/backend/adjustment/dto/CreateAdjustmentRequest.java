package com.hq.backend.adjustment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

// trigger_type을 요청 필드로 안 받는다 — 이 엔드포인트는 사용자가 직접 호출하는 API라
// trigger_type은 항상 "user_manual"로 서비스에서 고정한다. red_signal·streak_up은 AI가
// 판단해서 기록하는 값인데, 필드로 열어두면 사용자가 자기 조정을 AI가 감지한 것처럼
// 조작해서 기록을 남길 수 있다 — adjustments.trigger_type이 "왜 바뀌었는지"의 근거로
// 쓰이는 값이라 이 신뢰가 깨지면 안 된다. (TRD 6.7/138 — AI는 DB 롤로 직접 INSERT하는
// 별도 경로라 이 엔드포인트를 안 거친다.)
public record CreateAdjustmentRequest(
        @NotNull UUID routineTaskId,
        @NotNull UUID beforeActionId,
        @NotNull UUID afterActionId,
        @NotBlank String reason
) {
}
