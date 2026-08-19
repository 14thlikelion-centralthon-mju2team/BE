"""DWL and the daily closing card (TRD §7.1, §7.5 · PRD §14.5).

    DWL = 0.6 × (일정별 WIS의 야외시간 가중평균) + 0.4 × (일정별 RLS 평균)

``dwl_score``는 저장·응답에 포함하되 클라이언트는 표시하지 않습니다 (D5) — 숫자 노출은
건강 점수로 오해될 여지를 만듭니다. UI에는 밴드만 나갑니다.

템플릿 선택은 고정 우선순위입니다: `rushed > density > exposure > stable > default`.
관리 일정이 0건이면 카드를 만들지 않고, 야외 시간을 추정조차 할 수 없으면 숫자가 없는 문장을
씁니다 — 숫자를 지어내지 않습니다.
"""

from dataclasses import dataclass

from app.contracts.common import WellnessBand
from app.contracts.config import WellnessEngineConfig
from app.contracts.wellness import DailyEventSummary
from app.domain.wellness_engine.enums import CardScenario, WellnessDegraded
from app.domain.wellness_engine.templates import (
    CARD_MESSAGES,
    CARD_MESSAGES_WITHOUT_NUMBERS,
)


@dataclass(frozen=True)
class DailyLoad:
    event_count: int
    total_outdoor_minutes: int | None
    avg_wis_weighted: float | None
    avg_rls: float | None
    dwl_score: int | None
    dwl_band: WellnessBand | None
    card_scenario: CardScenario | None
    card_message: str | None
    card_visible: bool
    degraded: tuple[WellnessDegraded, ...]


def _weighted_wis(events: list[DailyEventSummary]) -> float | None:
    """야외 시간 가중 평균.  야외 시간이 전부 0이면 단순 평균으로 내려간다."""
    scored = [event for event in events if event.wis_score is not None]
    if not scored:
        return None

    total_weight = sum(event.outdoor_minutes or 0 for event in scored)
    if total_weight <= 0:
        return sum(event.wis_score or 0 for event in scored) / len(scored)

    weighted = sum(
        (event.wis_score or 0) * (event.outdoor_minutes or 0) for event in scored
    )
    return weighted / total_weight


def _average_rls(events: list[DailyEventSummary]) -> float | None:
    scored = [event.rush_load_score for event in events if event.rush_load_score is not None]
    if not scored:
        return None
    return sum(scored) / len(scored)


def _dwl_band(score: int, config: WellnessEngineConfig) -> WellnessBand:
    if score >= config.dwl_band_high:
        return WellnessBand.HIGH
    if score >= config.dwl_band_mid:
        return WellnessBand.MID
    return WellnessBand.LOW


def _combine(
    avg_wis: float | None,
    avg_rls: float | None,
    config: WellnessEngineConfig,
) -> int | None:
    """Renormalise when one term is missing rather than treating it as zero.

    A day with no WIS is not a day with zero environmental load — it is a day we
    could not measure.  Scaling the remaining term keeps the band meaningful.
    """
    terms: list[tuple[float, float]] = []
    if avg_wis is not None:
        terms.append((config.dwl_weight_wis, avg_wis))
    if avg_rls is not None:
        terms.append((config.dwl_weight_rls, avg_rls))
    if not terms:
        return None

    total_weight = sum(weight for weight, _ in terms)
    if total_weight <= 0:
        return None
    return min(100, round(sum(weight * value for weight, value in terms) / total_weight))


def _select_scenario(
    *,
    event_count: int,
    avg_rls: float | None,
    total_outdoor_minutes: int | None,
    band: WellnessBand | None,
    config: WellnessEngineConfig,
) -> CardScenario:
    if avg_rls is not None and avg_rls >= config.card_rushed_rls:
        return CardScenario.RUSHED
    if event_count >= config.card_density_event_count:
        return CardScenario.DENSITY
    if (
        total_outdoor_minutes is not None
        and total_outdoor_minutes >= config.card_exposure_outdoor_minutes
    ):
        return CardScenario.EXPOSURE
    if band is WellnessBand.LOW:
        return CardScenario.STABLE
    return CardScenario.DEFAULT


def summarize_daily_load(
    *,
    events: list[DailyEventSummary],
    config: WellnessEngineConfig,
) -> DailyLoad:
    degraded: list[WellnessDegraded] = []
    event_count = len(events)

    if event_count == 0:
        # 관리 일정 0건 → 카드 미노출 (§7.5).
        return DailyLoad(
            event_count=0,
            total_outdoor_minutes=None,
            avg_wis_weighted=None,
            avg_rls=None,
            dwl_score=None,
            dwl_band=None,
            card_scenario=None,
            card_message=None,
            card_visible=False,
            degraded=(),
        )

    outdoor_values = [
        event.outdoor_minutes for event in events if event.outdoor_minutes is not None
    ]
    total_outdoor = sum(outdoor_values) if outdoor_values else None
    if total_outdoor is None:
        degraded.append(WellnessDegraded.OUTDOOR_UNAVAILABLE)
    elif any(not event.outdoor_observed for event in events):
        degraded.append(WellnessDegraded.OUTDOOR_ESTIMATED)

    avg_wis = _weighted_wis(events)
    if avg_wis is None:
        degraded.append(WellnessDegraded.WIS_UNAVAILABLE)
    avg_rls = _average_rls(events)
    if avg_rls is None:
        degraded.append(WellnessDegraded.RLS_UNAVAILABLE)

    dwl_score = _combine(avg_wis, avg_rls, config)
    band = _dwl_band(dwl_score, config) if dwl_score is not None else None

    scenario = _select_scenario(
        event_count=event_count,
        avg_rls=avg_rls,
        total_outdoor_minutes=total_outdoor,
        band=band,
        config=config,
    )
    # 숫자를 지어내지 않는다: 야외 시간을 모르면 수치 없는 변형을 쓴다 (§7.5).
    template = (
        CARD_MESSAGES[scenario]
        if total_outdoor is not None
        else CARD_MESSAGES_WITHOUT_NUMBERS[scenario]
    )
    message = template.format(
        event_count=event_count,
        outdoor_minutes=total_outdoor if total_outdoor is not None else 0,
    )

    return DailyLoad(
        event_count=event_count,
        total_outdoor_minutes=total_outdoor,
        avg_wis_weighted=round(avg_wis, 2) if avg_wis is not None else None,
        avg_rls=round(avg_rls, 2) if avg_rls is not None else None,
        dwl_score=dwl_score,
        dwl_band=band,
        card_scenario=scenario,
        card_message=message,
        card_visible=True,
        degraded=tuple(degraded),
    )
