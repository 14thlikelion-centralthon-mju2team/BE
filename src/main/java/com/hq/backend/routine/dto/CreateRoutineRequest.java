package com.hq.backend.routine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

// actionIds는 1~2개로 제한한다 — PRD §7 공통 검증 계약 "상한: 행동 1~2개".
// "총시간 ≤ 가용 시간"은 여기서 검증하지 않는다: 가용 시간은 daily_checkins에만 있고
// 이 엔드포인트는 그 컨텍스트를 안 받는다. AI 루틴 초안 생성(feat/routine-draft, AI 담당)이
// daily_checkins를 이미 들고 있는 상태에서 호출되므로, 그쪽에서 검증하거나 여기 파라미터로
// 넘겨주는 방식으로 다음에 합친다.
public record CreateRoutineRequest(
        @NotBlank String title,
        @NotBlank String scheduleType, // "time" | "place" — DB check 제약과 동일한 값만 허용
        UUID placeId,
        String rrule,
        LocalTime anchorTime,
        @NotEmpty @Size(min = 1, max = 2) List<@NotNull UUID> actionIds
) {
}
