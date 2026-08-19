"""Approved sentence templates for ``adjustmentReason`` (TR-09, PRD §8.5).

Every string a user can read lives in this table.  The engine only substitutes
numeric slots — it never composes prose — so the medical-boundary copy lint
(TRD §17.5) has a finite list to check.  PRD §8.5 requires "왜 보정됐는지" in a
sentence; TRD §6.2 recommends storing it in
``USER_PREP_ESTIMATE.adjustment_reason``.

No template references an event title, a place, or a checklist item: the
personalization contract never receives them (절대 원칙 8, §14 최소 수집).
"""

from app.contracts.common import AdjustmentKnob, DelayCause
from app.domain.personalization_engine.adjustment import Adjustment
from app.domain.personalization_engine.attribution import Attribution
from app.domain.personalization_engine.enums import (
    ExclusionReason,
    PersonalizationDegraded,
)

TEMPLATES: dict[str, str] = {
    # ── excluded samples (§6.1, §6.4) ────────────────────────────────────────
    "excluded_incomplete_timestamps": (
        "준비 시작과 출발 기록이 모두 있어야 해서 이번 기록은 학습에서 제외했습니다."
    ),
    "excluded_clock_skew": (
        "기기 시각과 서버 시각 차이가 커서 이번 기록은 학습에서 제외했습니다."
    ),
    "excluded_arrival_result_unknown": (
        "도착 결과가 기록되지 않아 이번 기록은 학습에서 제외했습니다."
    ),
    "excluded_auto_manage_excluded": "자동 관리에서 제외된 일정이라 학습하지 않았습니다.",
    "excluded_prep_duration_outlier": (
        "준비 소요 시간이 정상 범위를 벗어나 이번 기록은 학습에서 제외했습니다."
    ),
    "excluded_geo_confidence_low": (
        "위치 기반 도착 판정의 신뢰도가 낮아 이번 기록은 학습에서 제외했습니다."
    ),
    "excluded_event_modified": (
        "일정이 변경돼 계획 기준이 무효가 되어 이번 기록은 학습에서 제외했습니다."
    ),
    "excluded_learning_reverted": (
        "사용자가 되돌린 기록이라 같은 보정을 다시 적용하지 않습니다."
    ),
    # ── prep estimate knob ───────────────────────────────────────────────────
    "prep_overrun_adjusted": (
        "준비에 예상보다 {signal}분 더 걸려 "
        "준비 시간 추정을 {previous}분에서 {new}분으로 조정했습니다."
    ),
    "prep_overrun_unchanged": (
        "준비에 예상보다 {signal}분 더 걸렸지만 준비 시간 추정은 {previous}분으로 유지했습니다."
    ),
    "no_delay_refined": (
        "지연 없이 완료돼 준비 시간 추정을 {previous}분에서 {new}분으로 정련했습니다."
    ),
    "no_delay_unchanged": "지연 없이 완료돼 준비 시간 추정을 {previous}분으로 유지했습니다.",
    "cold_start_hold": (
        "표본이 {sample_count}건뿐이라 초기 준비 시간 추정 {previous}분을 유지했습니다."
    ),
    # ── other knobs (§6.2 손잡이 라우팅) ─────────────────────────────────────
    "prep_late": (
        "준비 시작이 {signal}분 늦어 알림 선행 시간을 늘리도록 권고합니다. "
        "준비 시간 추정은 유지했습니다."
    ),
    "depart_late": (
        "준비를 마친 뒤 출발까지 {signal}분이 더 걸려 출발 알림을 강화하도록 권고합니다. "
        "준비 시간 추정은 유지했습니다."
    ),
    "traffic_adjusted": (
        "이동이 예상보다 {signal}분 더 걸려 "
        "교통 버퍼를 {previous}분에서 {new}분으로 조정했습니다."
    ),
    "traffic_unchanged": (
        "이동이 예상보다 {signal}분 더 걸렸지만 교통 버퍼는 {previous}분으로 유지했습니다."
    ),
}

#: Appended to the sentence when a guard-rail bound the result (§6.2).
GUARDRAIL_TEMPLATES: dict[PersonalizationDegraded, str] = {
    PersonalizationDegraded.STEP_LIMITED: " 1회 조정 상한 {step_limit}분을 적용했습니다.",
    PersonalizationDegraded.FLOOR_CLAMPED: " 하한 {floor}분을 적용했습니다.",
    PersonalizationDegraded.CEILING_CLAMPED: " 상한 {ceiling}분을 적용했습니다.",
    PersonalizationDegraded.SEED_FALLBACK: (
        " 초기 준비 시간이 없어 기본값 {seed}분을 기준으로 삼았습니다."
    ),
}

#: Exclusion sentence priority — the first matching reason is the one shown.
_EXCLUSION_PRIORITY: tuple[ExclusionReason, ...] = (
    ExclusionReason.EVENT_MODIFIED,
    ExclusionReason.LEARNING_REVERTED,
    ExclusionReason.INCOMPLETE_TIMESTAMPS,
    ExclusionReason.CLOCK_SKEW,
    ExclusionReason.PREP_DURATION_OUTLIER,
    ExclusionReason.GEO_CONFIDENCE_LOW,
    ExclusionReason.ARRIVAL_RESULT_UNKNOWN,
    ExclusionReason.AUTO_MANAGE_EXCLUDED,
)


def format_minutes(value: float) -> str:
    """Render minutes without a trailing ``.0`` so sentences read naturally."""
    rounded = round(value, 1)
    if rounded == int(rounded):
        return str(int(rounded))
    return f"{rounded:.1f}"


def build_exclusion_reason(reasons: list[ExclusionReason]) -> str:
    """Sentence for an excluded sample.  Highest-priority reason wins."""
    for reason in _EXCLUSION_PRIORITY:
        if reason in reasons:
            return TEMPLATES[f"excluded_{reason.value}"]
    return TEMPLATES["excluded_arrival_result_unknown"]


def _guardrail_suffix(
    adjustment: Adjustment,
    *,
    floor: float,
    ceiling: float,
) -> str:
    suffix = ""
    for degraded in adjustment.degraded:
        template = GUARDRAIL_TEMPLATES.get(degraded)
        if template is None:
            continue
        suffix += template.format(
            step_limit=format_minutes(adjustment.step_limit_minutes or 0.0),
            floor=format_minutes(floor),
            ceiling=format_minutes(ceiling),
            seed=format_minutes(adjustment.seed_minutes),
        )
    return suffix


def build_adjustment_reason(
    *,
    attribution: Attribution,
    adjustment: Adjustment,
    sample_count: int,
    floor: float,
    ceiling: float,
) -> str:
    """Sentence for an eligible sample, chosen by cause and by what moved."""
    signal_minutes = next(
        (
            candidate.signal_minutes
            for candidate in attribution.candidates
            if candidate.cause is attribution.cause
        ),
        0.0,
    )
    slots = {
        "signal": format_minutes(signal_minutes),
        "previous": format_minutes(adjustment.previous_value or 0.0),
        "new": format_minutes(adjustment.new_value or 0.0),
        "sample_count": str(sample_count),
    }

    if PersonalizationDegraded.COLD_START_HOLD in adjustment.degraded:
        key = "cold_start_hold"
    elif attribution.cause is DelayCause.PREP_LATE:
        key = "prep_late"
    elif attribution.cause is DelayCause.DEPART_LATE:
        key = "depart_late"
    elif attribution.cause is DelayCause.TRAFFIC:
        key = (
            "traffic_adjusted"
            if adjustment.knob is AdjustmentKnob.TRAFFIC_BUFFER
            else "traffic_unchanged"
        )
    elif attribution.cause is DelayCause.PREP_OVERRUN:
        key = (
            "prep_overrun_adjusted"
            if adjustment.knob is AdjustmentKnob.PREP_ESTIMATE
            else "prep_overrun_unchanged"
        )
    else:  # UNKNOWN — no delay signal cleared the noise floor
        key = (
            "no_delay_refined"
            if adjustment.knob is AdjustmentKnob.PREP_ESTIMATE
            else "no_delay_unchanged"
        )

    sentence = TEMPLATES[key].format(**slots)
    return sentence + _guardrail_suffix(adjustment, floor=floor, ceiling=ceiling)
