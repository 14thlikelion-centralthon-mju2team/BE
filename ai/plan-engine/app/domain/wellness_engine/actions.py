"""Action mapping and merge (TRD §7.3 · §5.4 · PRD §14.6).

    환경 스냅샷 → WIS → 밴드 판정 → 행동 매핑 → 중복 제거 → 템플릿 카피

PRD §14.6 규칙표를 그대로 옮긴 트리거 표입니다.

| 조건 | 외출 전 행동 | 일정 중 이벤트 |
|---|---|---|
| 자외선 + 야외 이동 | `uv_protect` | `uv_reapply` |
| 미세먼지 높음 | `pm_mask` | `pm_recheck` |
| 폭염·높은 체감온도 | `temp_heat_prep` | `hydration_intake` |
| 한파·큰 일교차 | `temp_cold_prep` | 없음 (PRD "추가 푸시 없음") |
| 강수 | `rain_gear` | `hydration_intake` (노출이 길면) |

``actions``는 준비 카드에 나가는 **외출 전 행동만** 담습니다. 일정 중 행동은 푸시 후보이고
``armed_action_code``로 따로 보고합니다 — 같은 항목을 카드와 푸시에 두 번 세지 않기 위해서입니다.

밴드별 개수는 low 0개, mid ``mid_band_action_cap``개, high 3개입니다. 상한 3은 ERD
``ck_wellness_rank``가 강제하는 값입니다.
"""

from dataclasses import dataclass

from app.contracts.common import WellnessBand, WellnessTopic
from app.contracts.config import WellnessEngineConfig
from app.contracts.wellness import MAX_WELLNESS_ACTIONS, PrepItemSnapshot
from app.domain.plan_engine.models import EnvironmentSnapshot
from app.domain.wellness_engine.enums import (
    PmBucket,
    RainBucket,
    TempBucket,
    UvBucket,
    WellnessActionCode,
)
from app.domain.wellness_engine.quantize import EnvironmentBuckets
from app.domain.wellness_engine.templates import (
    ACTION_LABELS,
    ACTION_REASONS,
    MERGE_KEYWORDS,
    MERGED_REASON_PREFIX,
    PM_GRADE_LABELS,
)

#: Which topic each action belongs to, for the contract's ``wellnessTopic``.
ACTION_TOPICS: dict[WellnessActionCode, WellnessTopic] = {
    WellnessActionCode.UV_PROTECT: WellnessTopic.UV,
    WellnessActionCode.UV_REAPPLY: WellnessTopic.UV,
    WellnessActionCode.PM_MASK: WellnessTopic.PM,
    WellnessActionCode.PM_RECHECK: WellnessTopic.PM,
    WellnessActionCode.TEMP_HEAT_PREP: WellnessTopic.TEMP,
    WellnessActionCode.TEMP_COLD_PREP: WellnessTopic.TEMP,
    WellnessActionCode.RAIN_GEAR: WellnessTopic.RAIN,
    WellnessActionCode.HYDRATION_INTAKE: WellnessTopic.HYDRATION,
}

#: Tie-break order when two actions have the same weighted contribution.
TOPIC_ORDER: tuple[WellnessTopic, ...] = (
    WellnessTopic.UV,
    WellnessTopic.PM,
    WellnessTopic.TEMP,
    WellnessTopic.RAIN,
    WellnessTopic.HYDRATION,
)


@dataclass(frozen=True)
class SelectedAction:
    code: WellnessActionCode
    topic: WellnessTopic
    label: str
    reason: str
    merged_item_id: str | None = None

    @property
    def merged(self) -> bool:
        return self.merged_item_id is not None


def _normalize_name(name: str) -> str:
    """Same normalisation as the plan engine's checklist merge (§5.4).

    Whitespace and case only — no semantic inference.  ``우 산`` and ``우산``
    stay different items there, and the same rule holds here.
    """
    return " ".join(name.split()).casefold()


def triggered_pre_departure(
    buckets: EnvironmentBuckets,
    outdoor_minutes: int,
) -> list[WellnessActionCode]:
    """Pre-departure actions the environment triggers (PRD §14.6 column 1)."""
    codes: list[WellnessActionCode] = []
    outdoors = outdoor_minutes > 0

    if buckets.uv is UvBucket.HIGH and outdoors:
        codes.append(WellnessActionCode.UV_PROTECT)
    if buckets.pm in (PmBucket.BAD, PmBucket.VERY_BAD):
        codes.append(WellnessActionCode.PM_MASK)
    if buckets.temp is TempBucket.HOT:
        codes.append(WellnessActionCode.TEMP_HEAT_PREP)
    if buckets.temp is TempBucket.COLD or buckets.temp_swing:
        codes.append(WellnessActionCode.TEMP_COLD_PREP)
    if buckets.rain is not RainBucket.NONE:
        codes.append(WellnessActionCode.RAIN_GEAR)
    return codes


def triggered_in_event(
    buckets: EnvironmentBuckets,
    outdoor_minutes: int,
) -> list[WellnessActionCode]:
    """In-event push candidates (PRD §14.6 column 2).

    한파 has no entry: PRD says "기본적으로 추가 푸시 없음".
    """
    codes: list[WellnessActionCode] = []
    outdoors = outdoor_minutes > 0

    if buckets.uv is UvBucket.HIGH and outdoors:
        codes.append(WellnessActionCode.UV_REAPPLY)
    if buckets.pm in (PmBucket.BAD, PmBucket.VERY_BAD) and outdoors:
        codes.append(WellnessActionCode.PM_RECHECK)
    if buckets.temp is TempBucket.HOT or (
        buckets.rain is not RainBucket.NONE and outdoors
    ):
        codes.append(WellnessActionCode.HYDRATION_INTAKE)
    return codes


def _reason_for(
    code: WellnessActionCode,
    environment: EnvironmentSnapshot,
    outdoor_minutes: int,
) -> str:
    grade = environment.air_grade.value if environment.air_grade is not None else "good"
    return ACTION_REASONS[code].format(
        uv_index=_format_number(environment.uv_index),
        pm_grade=PM_GRADE_LABELS.get(grade, grade),
        feels_like=_format_number(environment.feels_like_celsius),
        precipitation=environment.precipitation_probability
        if environment.precipitation_probability is not None
        else 0,
        outdoor_minutes=outdoor_minutes,
    )


def _format_number(value: float | None) -> str:
    if value is None:
        return "-"
    return str(int(value)) if value == int(value) else f"{value:.1f}"


def action_cap(band: WellnessBand, config: WellnessEngineConfig) -> int:
    if band is WellnessBand.LOW:
        return 0
    if band is WellnessBand.MID:
        return min(config.mid_band_action_cap, MAX_WELLNESS_ACTIONS)
    return MAX_WELLNESS_ACTIONS


def select_actions(
    *,
    band: WellnessBand,
    buckets: EnvironmentBuckets,
    environment: EnvironmentSnapshot,
    outdoor_minutes: int,
    contributions: dict[WellnessTopic, float],
    existing_prep_items: list[PrepItemSnapshot],
    config: WellnessEngineConfig,
) -> list[SelectedAction]:
    """Rank, cap and merge the triggered pre-departure actions.

    Ranking is by weighted contribution to WIS, so the factor that actually
    drove the score comes first.  Ties fall back to ``TOPIC_ORDER``.
    """
    cap = action_cap(band, config)
    if cap == 0:
        return []

    codes = triggered_pre_departure(buckets, outdoor_minutes)
    codes.sort(
        key=lambda code: (
            -contributions.get(ACTION_TOPICS[code], 0.0),
            TOPIC_ORDER.index(ACTION_TOPICS[code]),
        )
    )

    selected: list[SelectedAction] = []
    used_item_ids: set[str] = set()
    for code in codes[:cap]:
        merged_item = _find_mergeable_item(code, existing_prep_items, used_item_ids)
        reason = _reason_for(code, environment, outdoor_minutes)
        if merged_item is not None:
            used_item_ids.add(merged_item.item_id)
            reason = MERGED_REASON_PREFIX + reason
        selected.append(
            SelectedAction(
                code=code,
                topic=ACTION_TOPICS[code],
                label=ACTION_LABELS[code],
                reason=reason,
                merged_item_id=merged_item.item_id if merged_item is not None else None,
            )
        )
    return selected


def _find_mergeable_item(
    code: WellnessActionCode,
    existing_prep_items: list[PrepItemSnapshot],
    used_item_ids: set[str],
) -> PrepItemSnapshot | None:
    """Find the user's own item that already covers this action (§5.4, 골든 09).

    Sensitive items are skipped entirely: the engine must not recommend or
    annotate them (절대 원칙 3, PRD §14.8).
    """
    keywords = MERGE_KEYWORDS.get(code, ())
    for item in existing_prep_items:
        if item.is_sensitive or item.item_id in used_item_ids:
            continue
        name = _normalize_name(item.item_name)
        if any(_normalize_name(keyword) in name for keyword in keywords):
            return item
    return None
