"""Deterministic plan calculation.

Pure domain code: the same input and config always produce the same output.
There is no clock, no I/O, and no environment access here — the caller injects
``now`` and every configuration value through ``PlanInput``.
"""

from datetime import datetime, timedelta

from app.domain.plan_engine.checklist import merge_checklist, routine_minutes_of
from app.domain.plan_engine.confidence import degraded_reasons, prediction_confidence
from app.domain.plan_engine.constraints import evaluate_feasibility
from app.domain.plan_engine.enums import AnchorMode
from app.domain.plan_engine.models import (
    PlanBreakdown,
    PlanInput,
    PlanOutput,
)
from app.domain.plan_engine.reasons import build_reasons
from app.domain.plan_engine.version import CALC_VERSION


class PlanInputError(ValueError):
    """Raised when the input passes field validation but is not computable."""


def _anchor_timestamps(plan_input: PlanInput) -> tuple[datetime, datetime, bool]:
    """Return (recommended_depart_at, target_arrive_at, buffers_applied)."""
    route_minutes = plan_input.selected_route.total_minutes

    if plan_input.event.anchor_mode is AnchorMode.ARRIVE_BY:
        target_arrive_at = plan_input.event.starts_at - timedelta(
            minutes=plan_input.arrival_buffer_minutes
        )
        recommended_depart_at = target_arrive_at - timedelta(
            minutes=route_minutes + plan_input.traffic_buffer_minutes
        )
        return recommended_depart_at, target_arrive_at, True

    fixed_depart_at = plan_input.event.fixed_depart_at
    if fixed_depart_at is None:
        raise PlanInputError("fixed_depart_at is required when anchor_mode is depart_at")

    # A fixed departure time anchors the plan, so neither buffer shifts it.
    return fixed_depart_at, fixed_depart_at + timedelta(minutes=route_minutes), False


def compute_plan(plan_input: PlanInput) -> PlanOutput:
    config = plan_input.config
    prep_estimate = plan_input.prep_estimate

    if prep_estimate is None:
        estimated_prep_minutes = config.seed_fallback_minutes
        prep_source = "fallback"
        prep_text = f"준비 시간 추정이 없어 기본값 {estimated_prep_minutes}분을 사용했습니다."
    else:
        estimated_prep_minutes = prep_estimate.estimated_minutes
        prep_source = prep_estimate.source
        prep_text = (
            f"{prep_estimate.source} 기준 준비 시간 {estimated_prep_minutes}분을 사용했습니다."
        )

    # Only timed routines consume preparation time (prompt §10).
    personal_routine_minutes = sum(routine_minutes_of(item) for item in plan_input.prep_items)
    routine_names = [
        item.item_name.strip()
        for item in plan_input.prep_items
        if routine_minutes_of(item) > 0
    ]
    routine_text = (
        f"시간이 필요한 루틴({', '.join(routine_names)})에 "
        f"{personal_routine_minutes}분을 합산했습니다."
        if routine_names
        else "시간이 필요한 개인 루틴이 없어 0분을 합산했습니다."
    )

    environment = plan_input.environment
    precipitation = environment.precipitation_probability if environment is not None else None
    rain_applied = (
        precipitation is not None and precipitation >= config.rain_threshold_percent
    )
    extra_prep_minutes = config.rain_extra_prep_minutes if rain_applied else 0
    rain_reason = (
        f"강수 확률 {precipitation}%로 준비 시간 {extra_prep_minutes}분을 추가했습니다."
        if rain_applied
        else None
    )
    if rain_reason is not None:
        extra_prep_text = rain_reason
    elif environment is None:
        extra_prep_text = "환경 정보가 없어 추가 준비 시간을 적용하지 않았습니다."
    else:
        extra_prep_text = (
            f"강수 확률이 기준({config.rain_threshold_percent}%) 미만이라 "
            "추가 준비 시간을 적용하지 않았습니다."
        )

    recommended_depart_at, target_arrive_at, buffers_applied = _anchor_timestamps(plan_input)
    prep_start_at = recommended_depart_at - timedelta(
        minutes=estimated_prep_minutes + extra_prep_minutes + personal_routine_minutes
    )

    # Report only the minutes that actually moved a timestamp, so the three
    # timestamps stay reconstructable from the breakdown.
    applied_traffic_buffer = plan_input.traffic_buffer_minutes if buffers_applied else 0
    applied_arrival_buffer = plan_input.arrival_buffer_minutes if buffers_applied else 0
    if buffers_applied:
        traffic_buffer_text = f"교통 버퍼 {applied_traffic_buffer}분을 적용했습니다."
        arrival_buffer_text = f"도착 여유 {applied_arrival_buffer}분을 적용했습니다."
    else:
        traffic_buffer_text = (
            f"출발 시각이 고정된 일정이라 교통 버퍼 "
            f"{plan_input.traffic_buffer_minutes}분을 적용하지 않았습니다."
        )
        arrival_buffer_text = (
            f"출발 시각이 고정된 일정이라 도착 여유 "
            f"{plan_input.arrival_buffer_minutes}분을 적용하지 않았습니다."
        )

    reasons = build_reasons(
        prep_source=prep_source,
        prep_text=prep_text,
        routine_text=routine_text,
        travel_minutes=plan_input.selected_route.total_minutes,
        route_source=plan_input.selected_route.source,
        traffic_buffer_text=traffic_buffer_text,
        arrival_buffer_text=arrival_buffer_text,
        extra_prep_text=extra_prep_text,
        rain_applied=rain_applied,
    )

    feasible, constraint_reasons = evaluate_feasibility(
        now=plan_input.now,
        event_starts_at=plan_input.event.starts_at,
        prep_start_at=prep_start_at,
        recommended_depart_at=recommended_depart_at,
        target_arrive_at=target_arrive_at,
    )
    reasons.extend(constraint_reasons)

    degraded = degraded_reasons(plan_input)

    return PlanOutput(
        prep_start_at=prep_start_at,
        recommended_depart_at=recommended_depart_at,
        target_arrive_at=target_arrive_at,
        breakdown=PlanBreakdown(
            estimated_prep_minutes=estimated_prep_minutes,
            extra_prep_minutes=extra_prep_minutes,
            personal_routine_minutes=personal_routine_minutes,
            travel_minutes=plan_input.selected_route.total_minutes,
            traffic_buffer_minutes=applied_traffic_buffer,
            arrival_buffer_minutes=applied_arrival_buffer,
        ),
        reasons=reasons,
        checklist=merge_checklist(plan_input.prep_items, rain_reason),
        feasible=feasible,
        prediction_confidence=prediction_confidence(degraded),
        degraded=degraded,
        calc_version=CALC_VERSION,
    )
