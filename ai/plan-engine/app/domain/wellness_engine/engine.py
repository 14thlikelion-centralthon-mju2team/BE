"""Wellness engine entry points (TRD §7).

    evaluate_wellness  → WIS · 정규화 부하 · 준비 카드 행동 · 푸시 예약 판정
    compute_rush_load  → RLS
    summarize_day      → DWL · 일일 마무리 카드

Pure and total: every input that passed schema validation gets an answer.
환경 데이터가 없으면 계산을 멈추는 대신 웰니스만 생략하고 ``degraded``에 남깁니다 —
시간 계획은 Plan Engine에서 정상적으로 계속됩니다 (§5.1, §11.5).
"""

from app.contracts.wellness import (
    DailySummaryInput,
    DailySummaryOutput,
    NormalizedWellnessLoads,
    QuantizedEnvironment,
    RushLoadInput,
    RushLoadOutput,
    WellnessAction,
    WellnessInput,
    WellnessOutput,
)
from app.domain.wellness_engine.actions import select_actions
from app.domain.wellness_engine.arming import evaluate_arming
from app.domain.wellness_engine.dwl import summarize_daily_load
from app.domain.wellness_engine.enums import ArmingGate, WellnessDegraded
from app.domain.wellness_engine.normalize import normalize
from app.domain.wellness_engine.quantize import quantize
from app.domain.wellness_engine.rls import compute_rush_load_score
from app.domain.wellness_engine.version import WEIGHT_VERSION
from app.domain.wellness_engine.wis import topic_contributions, wis_band, wis_score

#: Config fields each endpoint actually reads.  ``weight_version`` is an echo of
#: the ENGINE_CONFIG row and takes no part in any result, so omitting it cannot
#: degrade anything.  Mirrors ``ENGINE_USED_CONFIG_FIELDS`` in M1.
EVALUATE_USED_CONFIG_FIELDS = frozenset(
    {
        "wis_weight_uv",
        "wis_weight_pm",
        "wis_weight_temp",
        "wis_weight_outdoor",
        "interest_boost_max",
        "outdoor_cap_minutes",
        "wis_band_card",
        "wis_band_event",
        "uv_high_index",
        "uv_full_load_index",
        "pm_load_moderate",
        "pm_load_bad",
        "pm_load_very_bad",
        "comfort_min_celsius",
        "comfort_max_celsius",
        "heat_extreme_celsius",
        "cold_extreme_celsius",
        "rain_light_percent",
        "rain_heavy_percent",
        "rain_thermal_bonus",
        "temp_swing_flag_celsius",
        "wellness_event_min",
        "wellness_event_min_raised",
        "daily_event_cap_default",
        "mid_band_action_cap",
    }
)

RUSH_LOAD_USED_CONFIG_FIELDS = frozenset(
    {
        "rls_weight_dp",
        "rls_weight_dd",
        "rls_weight_e",
        "rls_delay_full_load_minutes",
        "rls_critical_alert_full_count",
    }
)

DAILY_SUMMARY_USED_CONFIG_FIELDS = frozenset(
    {
        "dwl_weight_wis",
        "dwl_weight_rls",
        "dwl_band_mid",
        "dwl_band_high",
        "card_rushed_rls",
        "card_density_event_count",
        "card_exposure_outdoor_minutes",
    }
)


def _dedupe(values: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value not in seen:
            seen.add(value)
            result.append(value)
    return result


def _config_fallback(used: frozenset[str], provided: set[str]) -> list[str]:
    if used - provided:
        return [WellnessDegraded.CONFIG_FALLBACK.value]
    return []


def evaluate_wellness(payload: WellnessInput) -> WellnessOutput:
    """WIS, card actions and the TR-11 arming decision for one event."""
    config = payload.config
    degraded = _config_fallback(EVALUATE_USED_CONFIG_FIELDS, config.model_fields_set)

    if payload.environment is None:
        # 환경 API 전면 실패 → 웰니스만 생략한다. 시간 계획은 영향받지 않는다 (§11.5).
        return WellnessOutput(
            wis_score=None,
            wis_band=None,
            normalized_loads=None,
            actions=[],
            event_armed=False,
            weight_version=WEIGHT_VERSION,
            degraded=_dedupe([WellnessDegraded.ENV_UNAVAILABLE.value] + degraded),
            quantized=None,
            armed_action_code=None,
            arming_blocked_by=[ArmingGate.NO_CANDIDATE.value],
        )

    environment = payload.environment
    buckets = quantize(environment, config)
    loads = normalize(
        environment=environment,
        buckets=buckets,
        estimated_outdoor_minutes=payload.estimated_outdoor_minutes,
        preferences=payload.user_preferences,
        config=config,
    )
    degraded = [item.value for item in loads.degraded] + degraded

    score = wis_score(loads, config)
    band = wis_band(score, config) if score is not None else None
    contributions = topic_contributions(loads, config)
    outdoor_minutes = payload.estimated_outdoor_minutes or 0

    actions = (
        select_actions(
            band=band,
            buckets=buckets,
            environment=environment,
            outdoor_minutes=outdoor_minutes,
            contributions=contributions,
            existing_prep_items=payload.existing_prep_items,
            config=config,
        )
        if band is not None
        else []
    )

    arming = evaluate_arming(
        wis=score,
        buckets=buckets,
        outdoor_minutes=outdoor_minutes,
        contributions=contributions,
        state=payload.event_state,
        preferences=payload.user_preferences,
        config=config,
    )

    return WellnessOutput(
        wis_score=score,
        wis_band=band,
        # The contract requires a full set of loads, so they are only reported
        # when exposure time is known — the same condition as WIS itself.
        normalized_loads=(
            NormalizedWellnessLoads(
                uv_load=round(loads.uv, 4),
                pm_load=round(loads.pm, 4),
                thermal_load=round(loads.thermal, 4),
                outdoor_load=round(loads.outdoor, 4),
                interest_multiplier=round(loads.interest, 4),
            )
            if loads.outdoor is not None
            else None
        ),
        actions=[
            WellnessAction(
                wellness_topic=action.topic,
                action_code=action.code.value,
                action_label=action.label,
                display_rank=index + 1,
                reason=action.reason,
                merged_with_prep_item=action.merged,
                merged_item_id=action.merged_item_id,
            )
            for index, action in enumerate(actions)
        ],
        event_armed=arming.armed,
        weight_version=WEIGHT_VERSION,
        degraded=_dedupe(degraded),
        quantized=QuantizedEnvironment(
            rain=buckets.rain.value,
            uv=buckets.uv.value,
            pm=buckets.pm.value,
            temp=buckets.temp.value,
            temp_swing=buckets.temp_swing,
        ),
        armed_action_code=arming.action_code.value if arming.action_code else None,
        arming_blocked_by=[gate.value for gate in arming.blocked_by],
    )


def compute_rush_load(payload: RushLoadInput) -> RushLoadOutput:
    """RLS for one completed event (§7.1)."""
    result = compute_rush_load_score(
        prep_delay_minutes=payload.prep_delay_minutes,
        depart_delay_minutes=payload.depart_delay_minutes,
        critical_alert_count=payload.critical_alert_count,
        config=payload.config,
    )
    return RushLoadOutput(
        event_id=payload.event_id,
        rush_load_score=result.score,
        prep_delay_norm=result.prep_delay_norm,
        depart_delay_norm=result.depart_delay_norm,
        critical_alert_norm=result.critical_alert_norm,
        weight_version=WEIGHT_VERSION,
    )


def summarize_day(payload: DailySummaryInput) -> DailySummaryOutput:
    """DWL and the daily closing card (§7.5)."""
    daily = summarize_daily_load(events=payload.events, config=payload.config)
    degraded = [item.value for item in daily.degraded] + _config_fallback(
        DAILY_SUMMARY_USED_CONFIG_FIELDS, payload.config.model_fields_set
    )

    return DailySummaryOutput(
        summary_date=payload.summary_date,
        event_count=daily.event_count,
        total_outdoor_minutes=daily.total_outdoor_minutes,
        avg_wis_weighted=daily.avg_wis_weighted,
        avg_rls=daily.avg_rls,
        dwl_score=daily.dwl_score,
        dwl_band=daily.dwl_band,
        card_scenario=daily.card_scenario.value if daily.card_scenario else None,
        card_message=daily.card_message,
        card_visible=daily.card_visible,
        weight_version=WEIGHT_VERSION,
        degraded=_dedupe(degraded),
    )
