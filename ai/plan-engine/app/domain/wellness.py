"""Deterministic M3 wellness priority engine (no I/O or clock access)."""

from app.contracts.common import WellnessBand, WellnessTopic
from app.contracts.wellness import (
    NormalizedWellnessLoads,
    WellnessAction,
    WellnessInput,
    WellnessOutput,
)


def _clamp(value: float) -> float:
    return max(0.0, min(1.0, value))


def _interest_multiplier(payload: WellnessInput) -> float:
    enabled = sum(preference.is_enabled for preference in payload.user_preferences)
    return 1.0 + min(enabled / 5.0, 1.0) * (payload.config.interest_boost_max - 1.0)


def _preference_enabled(payload: WellnessInput, topic: WellnessTopic) -> bool:
    return any(
        preference.wellness_topic == topic and preference.is_enabled
        for preference in payload.user_preferences
    )


def evaluate(payload: WellnessInput) -> WellnessOutput:
    if payload.environment is None:
        return WellnessOutput(
            wis_score=None,
            wis_band=None,
            normalized_loads=None,
            actions=[],
            event_armed=False,
            weight_version=payload.config.weight_version,
            degraded=["env_unavailable"],
        )

    environment = payload.environment
    outdoor = (
        _clamp((payload.estimated_outdoor_minutes or 0) / payload.config.outdoor_cap_minutes)
        if payload.config.outdoor_cap_minutes
        else 0.0
    )
    loads = NormalizedWellnessLoads(
        uv_load=_clamp((environment.uv_index or 0.0) / 11.0),
        pm_load=_clamp((environment.pm10 or 0) / 150.0),
        thermal_load=_clamp(abs((environment.feels_like_celsius or 22.0) - 22.0) / 18.0),
        outdoor_load=outdoor,
        interest_multiplier=_interest_multiplier(payload),
    )
    raw = (
        (
            loads.uv_load * payload.config.wis_weight_uv
            + loads.pm_load * payload.config.wis_weight_pm
            + loads.thermal_load * payload.config.wis_weight_temp
            + loads.outdoor_load * payload.config.wis_weight_outdoor
        )
        * 100.0
        * loads.interest_multiplier
    )
    score = max(0, min(100, round(raw)))
    band = (
        WellnessBand.HIGH
        if score >= payload.config.wis_band_event
        else (WellnessBand.MID if score >= payload.config.wis_band_card else WellnessBand.LOW)
    )

    candidates: list[tuple[float, WellnessAction]] = []
    if loads.uv_load >= 0.25 and _preference_enabled(payload, WellnessTopic.UV):
        candidates.append(
            (
                loads.uv_load,
                WellnessAction(
                    wellness_topic=WellnessTopic.UV,
                    action_code="sunscreen",
                    action_label="선크림 재도포 준비",
                    display_rank=1,
                    reason="자외선 노출이 예상돼요",
                ),
            )
        )
    if loads.pm_load >= 0.25 and _preference_enabled(payload, WellnessTopic.PM):
        candidates.append(
            (
                loads.pm_load,
                WellnessAction(
                    wellness_topic=WellnessTopic.PM,
                    action_code="mask",
                    action_label="마스크 준비",
                    display_rank=1,
                    reason="미세먼지 노출이 예상돼요",
                ),
            )
        )
    if (environment.precipitation_probability or 0) >= 50 and _preference_enabled(
        payload, WellnessTopic.RAIN
    ):
        candidates.append(
            (
                environment.precipitation_probability / 100.0,
                WellnessAction(
                    wellness_topic=WellnessTopic.RAIN,
                    action_code="umbrella",
                    action_label="우산 확인",
                    display_rank=1,
                    reason="강수 가능성이 있어요",
                ),
            )
        )
    if loads.thermal_load >= 0.35 and _preference_enabled(payload, WellnessTopic.TEMP):
        candidates.append(
            (
                loads.thermal_load,
                WellnessAction(
                    wellness_topic=WellnessTopic.TEMP,
                    action_code="outerwear",
                    action_label="겉옷 확인",
                    display_rank=1,
                    reason="기온 변화가 예상돼요",
                ),
            )
        )
    if outdoor >= 0.25 and _preference_enabled(payload, WellnessTopic.HYDRATION):
        candidates.append(
            (
                outdoor,
                WellnessAction(
                    wellness_topic=WellnessTopic.HYDRATION,
                    action_code="hydration",
                    action_label="물 챙기기",
                    display_rank=1,
                    reason="야외 이동이 예상돼요",
                ),
            )
        )

    actions = [
        action.model_copy(update={"display_rank": rank})
        for rank, (_, action) in enumerate(
            sorted(candidates, key=lambda candidate: candidate[0], reverse=True)[:3], start=1
        )
    ]
    return WellnessOutput(
        wis_score=score,
        wis_band=band,
        normalized_loads=loads,
        actions=actions,
        event_armed=score >= payload.config.wis_band_event and bool(actions),
        weight_version=payload.config.weight_version,
        degraded=[],
    )
