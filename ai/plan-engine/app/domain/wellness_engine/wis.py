"""WIS — 일정 웰니스 우선순위 점수 (TRD §7.1 · PRD §14.3).

    WIS = min(100, 100 × (0.35·U + 0.25·P + 0.20·T + 0.20·O) × M)

WIS는 의료 위험도나 피부 상태 점수가 아닙니다. 어떤 웰니스 행동을 언제 노출할지 결정하는
내부 우선순위 값입니다 (절대 원칙 3, ERD ``score_purpose='priority_only'``).

| 밴드 | 범위 | 동작 |
|---|---|---|
| low | 0~39 | 조용히 — 일정 상세에만, 푸시 없음 |
| mid | 40~69 | 준비 카드에 행동 1~2개 |
| high | 70~100 | 행동 제안 + 웰니스 이벤트 푸시 후보 |
"""

from app.contracts.common import WellnessBand, WellnessTopic
from app.contracts.config import WellnessEngineConfig
from app.domain.wellness_engine.normalize import NormalizedLoads


def wis_score(loads: NormalizedLoads, config: WellnessEngineConfig) -> int | None:
    """WIS, or None when exposure time is unknown (§7.2 "경로 없으면 WIS 생략")."""
    if loads.outdoor is None:
        return None

    weighted = (
        config.wis_weight_uv * loads.uv
        + config.wis_weight_pm * loads.pm
        + config.wis_weight_temp * loads.thermal
        + config.wis_weight_outdoor * loads.outdoor
    )
    return min(100, round(100.0 * weighted * loads.interest))


def wis_band(score: int, config: WellnessEngineConfig) -> WellnessBand:
    if score >= config.wis_band_event:
        return WellnessBand.HIGH
    if score >= config.wis_band_card:
        return WellnessBand.MID
    return WellnessBand.LOW


def topic_contributions(
    loads: NormalizedLoads,
    config: WellnessEngineConfig,
) -> dict[WellnessTopic, float]:
    """Each topic's weighted contribution to WIS, used to rank actions.

    Rain and temperature share the thermal weight because §7.2 folds
    precipitation into T rather than giving it its own term.  Hydration follows
    heat and rain, so it inherits the same contribution.
    """
    thermal_contribution = config.wis_weight_temp * loads.thermal
    return {
        WellnessTopic.UV: config.wis_weight_uv * loads.uv,
        WellnessTopic.PM: config.wis_weight_pm * loads.pm,
        WellnessTopic.TEMP: thermal_contribution,
        WellnessTopic.RAIN: thermal_contribution,
        WellnessTopic.HYDRATION: thermal_contribution,
    }
