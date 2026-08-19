"""Environment quantisation (TRD §7.2, shared with the plan engine §5.5).

    rain : none | light(≥30%) | heavy(≥60%)
    uv   : low  | high(≥6)
    pm   : good | bad | veryBad
    temp : cold | mild | hot   (+ 일교차 플래그)

강수확률 61%와 63%는 같은 결정을 낳습니다. 원값을 해시에 넣으면 5분마다 리비전이 올라가므로,
행동이 갈리는 경계로 잘라 넣습니다. 원값은 ``PLAN_CONTEXT``에 그대로 보존되므로 설명가능성은
손상되지 않습니다.
"""

from dataclasses import dataclass

from app.contracts.config import WellnessEngineConfig
from app.domain.plan_engine.enums import AirQualityGrade
from app.domain.plan_engine.models import EnvironmentSnapshot
from app.domain.wellness_engine.enums import (
    PmBucket,
    RainBucket,
    TempBucket,
    UvBucket,
)


@dataclass(frozen=True)
class EnvironmentBuckets:
    rain: RainBucket
    uv: UvBucket
    pm: PmBucket
    temp: TempBucket
    #: True when the day's feels-like swing crosses the flag threshold.
    temp_swing: bool


def rain_bucket(
    precipitation_probability: int | None,
    config: WellnessEngineConfig,
) -> RainBucket:
    if precipitation_probability is None:
        return RainBucket.NONE
    if precipitation_probability >= config.rain_heavy_percent:
        return RainBucket.HEAVY
    if precipitation_probability >= config.rain_light_percent:
        return RainBucket.LIGHT
    return RainBucket.NONE


def uv_bucket(uv_index: float | None, config: WellnessEngineConfig) -> UvBucket:
    if uv_index is None:
        return UvBucket.LOW
    return UvBucket.HIGH if uv_index >= config.uv_high_index else UvBucket.LOW


def pm_bucket(grade: AirQualityGrade | None) -> PmBucket:
    # 좋음과 보통은 같은 결정을 낳으므로 한 버킷을 공유한다 (§7.2 pm: good).
    if grade is AirQualityGrade.VERY_BAD:
        return PmBucket.VERY_BAD
    if grade is AirQualityGrade.BAD:
        return PmBucket.BAD
    return PmBucket.GOOD


def temp_bucket(
    feels_like_celsius: float | None,
    config: WellnessEngineConfig,
) -> TempBucket:
    if feels_like_celsius is None:
        return TempBucket.MILD
    if feels_like_celsius > config.comfort_max_celsius:
        return TempBucket.HOT
    if feels_like_celsius < config.comfort_min_celsius:
        return TempBucket.COLD
    return TempBucket.MILD


def temp_swing_flag(
    environment: EnvironmentSnapshot,
    config: WellnessEngineConfig,
) -> bool:
    low = environment.feels_like_min_celsius
    high = environment.feels_like_max_celsius
    if low is None or high is None:
        return False
    return (high - low) >= config.temp_swing_flag_celsius


def quantize(
    environment: EnvironmentSnapshot,
    config: WellnessEngineConfig,
) -> EnvironmentBuckets:
    return EnvironmentBuckets(
        rain=rain_bucket(environment.precipitation_probability, config),
        uv=uv_bucket(environment.uv_index, config),
        pm=pm_bucket(environment.air_grade),
        temp=temp_bucket(environment.feels_like_celsius, config),
        temp_swing=temp_swing_flag(environment, config),
    )
