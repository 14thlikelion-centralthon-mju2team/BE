"""Input normalisation for WIS (TRD §7.2).

PRD said "0~1 정규화값" and stopped there; the boundaries below are what §7.2
proposes, and all of them are remote config (TR-06).

| 항 | 정규화 | 데이터 부재 시 |
|---|---|---|
| U | 0→0 · 6→0.6 · 8→0.8 · 11+→1.0 선형 | U=0, degraded |
| P | 좋음 0 · 보통 0.25 · 나쁨 0.7 · 매우나쁨 1.0 | P=0, degraded |
| T | 쾌적(5~28℃) 0 → 폭염·한파 경계 1.0 선형, heavy rain이면 +0.3 후 클램프 | T=0 |
| O | min(1, 야외분 / 120) | 경로 없으면 **WIS 자체를 생략** |
| M | 기본 1.0 · 관심 항목 관련 시 최대 1.25 | 1.0 |

The engine never stops calculating.  Missing environment means a default and a
``degraded`` record; only a missing route removes WIS entirely, because a
wellness score without exposure time would be a guess dressed as a number.
"""

from dataclasses import dataclass

from app.contracts.common import WellnessTopic
from app.contracts.config import WellnessEngineConfig
from app.contracts.wellness import WellnessPreference
from app.domain.plan_engine.enums import AirQualityGrade
from app.domain.plan_engine.models import EnvironmentSnapshot
from app.domain.wellness_engine.enums import (
    RainBucket,
    TempBucket,
    WellnessDegraded,
)
from app.domain.wellness_engine.quantize import EnvironmentBuckets


@dataclass(frozen=True)
class NormalizedLoads:
    """U · P · T · O · M plus what had to be assumed."""

    uv: float
    pm: float
    thermal: float
    #: None when exposure time is unknown — the caller must then omit WIS.
    outdoor: float | None
    interest: float
    degraded: tuple[WellnessDegraded, ...]


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


def uv_load(
    uv_index: float | None,
    config: WellnessEngineConfig,
) -> tuple[float, tuple[WellnessDegraded, ...]]:
    if uv_index is None:
        return 0.0, (WellnessDegraded.UV_UNAVAILABLE,)
    return _clamp01(uv_index / config.uv_full_load_index), ()


def pm_load(
    grade: AirQualityGrade | None,
    config: WellnessEngineConfig,
) -> tuple[float, tuple[WellnessDegraded, ...]]:
    if grade is None:
        return 0.0, (WellnessDegraded.PM_UNAVAILABLE,)
    loads = {
        AirQualityGrade.GOOD: 0.0,
        AirQualityGrade.MODERATE: config.pm_load_moderate,
        AirQualityGrade.BAD: config.pm_load_bad,
        AirQualityGrade.VERY_BAD: config.pm_load_very_bad,
    }
    return _clamp01(loads[grade]), ()


def thermal_load(
    feels_like_celsius: float | None,
    rain: RainBucket,
    config: WellnessEngineConfig,
) -> tuple[float, tuple[WellnessDegraded, ...]]:
    """Thermal discomfort from feels-like temperature, plus heavy rain."""
    degraded: tuple[WellnessDegraded, ...] = ()
    if feels_like_celsius is None:
        base = 0.0
        degraded = (WellnessDegraded.TEMP_UNAVAILABLE,)
    elif feels_like_celsius > config.comfort_max_celsius:
        span = config.heat_extreme_celsius - config.comfort_max_celsius
        base = (
            _clamp01((feels_like_celsius - config.comfort_max_celsius) / span)
            if span > 0
            else 1.0
        )
    elif feels_like_celsius < config.comfort_min_celsius:
        span = config.comfort_min_celsius - config.cold_extreme_celsius
        base = (
            _clamp01((config.comfort_min_celsius - feels_like_celsius) / span)
            if span > 0
            else 1.0
        )
    else:
        base = 0.0

    if rain is RainBucket.HEAVY:
        base += config.rain_thermal_bonus
    return _clamp01(base), degraded


def outdoor_load(
    estimated_outdoor_minutes: int | None,
    config: WellnessEngineConfig,
) -> tuple[float | None, tuple[WellnessDegraded, ...]]:
    """None means "no route" — the caller omits WIS entirely (§7.2)."""
    if estimated_outdoor_minutes is None:
        return None, (WellnessDegraded.OUTDOOR_UNAVAILABLE,)
    return _clamp01(estimated_outdoor_minutes / config.outdoor_cap_minutes), ()


def relevant_topics(
    buckets: EnvironmentBuckets,
    uv: float,
    pm: float,
    thermal: float,
) -> frozenset[WellnessTopic]:
    """Topics today's environment actually speaks to.

    Used only for the interest multiplier.  Hydration follows heat or rain,
    matching PRD §14.6 where 수분 보충 appears under 폭염 and 강수.
    """
    topics: set[WellnessTopic] = set()
    if uv > 0.0:
        topics.add(WellnessTopic.UV)
    if pm > 0.0:
        topics.add(WellnessTopic.PM)
    if thermal > 0.0:
        topics.add(WellnessTopic.TEMP)
    if buckets.rain is not RainBucket.NONE:
        topics.add(WellnessTopic.RAIN)
    if buckets.temp is TempBucket.HOT or buckets.rain is not RainBucket.NONE:
        topics.add(WellnessTopic.HYDRATION)
    return frozenset(topics)


def interest_multiplier(
    preferences: list[WellnessPreference],
    topics: frozenset[WellnessTopic],
    config: WellnessEngineConfig,
) -> float:
    """M — 1.0 by default, up to ``interest_boost_max`` for a relevant interest.

    §7.2 says "관심 항목 관련 시 최대 1.25" without a gradation rule.  Resolved
    as a step: one enabled topic that today's environment speaks to lifts M to
    the maximum.  Splitting the boost across topics would make M depend on how
    many topics happened to trigger, which is not a property of the user's
    interest.  The single change point is this function.
    """
    for preference in preferences:
        if preference.is_enabled and preference.wellness_topic in topics:
            return config.interest_boost_max
    return 1.0


def normalize(
    environment: EnvironmentSnapshot,
    buckets: EnvironmentBuckets,
    estimated_outdoor_minutes: int | None,
    preferences: list[WellnessPreference],
    config: WellnessEngineConfig,
) -> NormalizedLoads:
    uv, uv_degraded = uv_load(environment.uv_index, config)
    pm, pm_degraded = pm_load(environment.air_grade, config)
    thermal, thermal_degraded = thermal_load(
        environment.feels_like_celsius, buckets.rain, config
    )
    outdoor, outdoor_degraded = outdoor_load(estimated_outdoor_minutes, config)

    rain_degraded: tuple[WellnessDegraded, ...] = (
        (WellnessDegraded.RAIN_UNAVAILABLE,)
        if environment.precipitation_probability is None
        else ()
    )

    topics = relevant_topics(buckets, uv, pm, thermal)
    return NormalizedLoads(
        uv=uv,
        pm=pm,
        thermal=thermal,
        outdoor=outdoor,
        interest=interest_multiplier(preferences, topics, config),
        degraded=uv_degraded
        + pm_degraded
        + thermal_degraded
        + rain_degraded
        + outdoor_degraded,
    )
