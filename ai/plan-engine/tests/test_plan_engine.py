from datetime import datetime

import pytest
from pydantic import ValidationError

from app.domain.plan_engine.engine import compute_plan
from app.domain.plan_engine.enums import (
    DegradedReason,
    PredictionConfidence,
    PrepActionType,
    PrepSourceType,
)
from app.domain.plan_engine.version import CALC_VERSION
from app.schemas.plan import (
    EngineConfig,
    EnvironmentSnapshot,
    EventSnapshot,
    PrepItemSnapshot,
    RouteSnapshot,
)
from tests.conftest import SEOUL, make_input


def test_basic_arrive_by_calculation() -> None:
    result = compute_plan(make_input(environment=EnvironmentSnapshot(precipitation_probability=10)))

    assert result.target_arrive_at == datetime(2026, 8, 20, 13, 50, tzinfo=SEOUL)
    assert result.recommended_depart_at == datetime(2026, 8, 20, 13, 5, tzinfo=SEOUL)
    assert result.prep_start_at == datetime(2026, 8, 20, 12, 35, tzinfo=SEOUL)
    assert result.feasible is True
    assert result.prediction_confidence is PredictionConfidence.HIGH


def test_depart_at_calculation() -> None:
    event = EventSnapshot(
        starts_at=datetime(2026, 8, 20, 14, tzinfo=SEOUL),
        anchor_mode="depart_at",
        fixed_depart_at=datetime(2026, 8, 20, 13, tzinfo=SEOUL),
    )
    result = compute_plan(make_input(event=event))

    assert result.recommended_depart_at == datetime(2026, 8, 20, 13, tzinfo=SEOUL)
    assert result.target_arrive_at == datetime(2026, 8, 20, 13, 40, tzinfo=SEOUL)
    assert result.prep_start_at == datetime(2026, 8, 20, 12, 30, tzinfo=SEOUL)


def test_depart_at_reports_unused_buffers_as_zero() -> None:
    """A fixed departure ignores both buffers, so reasons must say so."""
    event = EventSnapshot(
        starts_at=datetime(2026, 8, 20, 14, tzinfo=SEOUL),
        anchor_mode="depart_at",
        fixed_depart_at=datetime(2026, 8, 20, 13, tzinfo=SEOUL),
    )
    result = compute_plan(make_input(event=event))

    assert result.breakdown.traffic_buffer_minutes == 0
    assert result.breakdown.arrival_buffer_minutes == 0
    texts = {reason.field: reason.text for reason in result.reasons}
    assert "적용하지 않았습니다" in texts["arrivalBufferMinutes"]
    assert "적용하지 않았습니다" in texts["trafficBufferMinutes"]


def test_depart_at_can_be_infeasible() -> None:
    event = EventSnapshot(
        starts_at=datetime(2026, 8, 20, 14, tzinfo=SEOUL),
        anchor_mode="depart_at",
        fixed_depart_at=datetime(2026, 8, 20, 13, 30, tzinfo=SEOUL),
    )
    result = compute_plan(make_input(event=event))

    # 13:30 + 40m arrives at 14:10, after the event start.
    assert result.feasible is False
    assert result.target_arrive_at == datetime(2026, 8, 20, 14, 10, tzinfo=SEOUL)
    assert any("일정 시작 시각보다 늦습니다" in reason.text for reason in result.reasons)


def test_missing_prep_estimate_uses_seed_and_degrades() -> None:
    result = compute_plan(make_input(prep_estimate=None))

    assert result.breakdown.estimated_prep_minutes == 30
    assert DegradedReason.PREP_ESTIMATE_MISSING in result.degraded
    assert result.prediction_confidence is PredictionConfidence.LOW


def test_missing_environment_does_not_stop_time_plan() -> None:
    result = compute_plan(make_input(environment=None))

    assert result.feasible is True
    assert result.breakdown.extra_prep_minutes == 0
    assert DegradedReason.ENV_UNAVAILABLE in result.degraded
    assert result.prediction_confidence is PredictionConfidence.MID


def test_stale_route_degrades_confidence() -> None:
    route = RouteSnapshot(
        route_id="route-1", total_minutes=40, source="odsay", is_stale=True
    )
    result = compute_plan(make_input(selected_route=route))

    assert DegradedReason.ROUTE_STALE in result.degraded
    assert result.prediction_confidence is PredictionConfidence.LOW


def test_rain_adds_prep_and_umbrella() -> None:
    environment = EnvironmentSnapshot(precipitation_probability=70)
    result = compute_plan(make_input(environment=environment))

    assert result.breakdown.extra_prep_minutes == 5
    assert result.prep_start_at == datetime(2026, 8, 20, 12, 30, tzinfo=SEOUL)
    assert [item.item_name for item in result.checklist] == ["우산"]
    assert any("강수 확률 70%" in reason.text for reason in result.reasons)


def test_user_umbrella_is_merged_with_weather_item() -> None:
    umbrella = PrepItemSnapshot(
        item_id="umbrella",
        item_name="  우산  ",
        action_type=PrepActionType.CARRY,
        source_type=PrepSourceType.RULE,
    )
    result = compute_plan(
        make_input(
            environment=EnvironmentSnapshot(precipitation_probability=70),
            prep_items=[umbrella],
        )
    )

    assert [item.item_name for item in result.checklist] == ["우산"]
    assert result.checklist[0].source_type is PrepSourceType.RULE
    assert "강수 확률 70%" in (result.checklist[0].reason or "")


def test_timed_routines_are_added_to_prep_time() -> None:
    items = [
        PrepItemSnapshot(
            item_id="lens",
            item_name="렌즈 착용",
            action_type=PrepActionType.TIMED_ROUTINE,
            source_type=PrepSourceType.RULE,
            applied_minutes=5,
        ),
        PrepItemSnapshot(
            item_id="makeup",
            item_name="화장",
            action_type=PrepActionType.TIMED_ROUTINE,
            source_type=PrepSourceType.RULE,
            applied_minutes=10,
        ),
    ]
    result = compute_plan(make_input(prep_items=items))

    assert result.breakdown.personal_routine_minutes == 15
    assert result.prep_start_at == datetime(2026, 8, 20, 12, 20, tzinfo=SEOUL)


@pytest.mark.parametrize(
    "action_type",
    [PrepActionType.CARRY, PrepActionType.CONSUME, PrepActionType.PURCHASE],
)
def test_non_timed_items_do_not_change_prep_time(action_type: PrepActionType) -> None:
    item = PrepItemSnapshot(
        item_id="item",
        item_name="개인 항목",
        action_type=action_type,
        source_type=PrepSourceType.RULE,
        applied_minutes=99,
    )
    result = compute_plan(make_input(prep_items=[item]))

    assert result.breakdown.personal_routine_minutes == 0
    assert result.prep_start_at == datetime(2026, 8, 20, 12, 35, tzinfo=SEOUL)
    assert result.checklist[0].applied_minutes == 0


def test_infeasible_plan_preserves_calculated_times() -> None:
    result = compute_plan(
        make_input(now=datetime(2026, 8, 20, 13, 30, tzinfo=SEOUL))
    )

    assert result.feasible is False
    assert result.recommended_depart_at == datetime(2026, 8, 20, 13, 5, tzinfo=SEOUL)
    assert any("권장 출발 시각을 지났습니다" in reason.text for reason in result.reasons)


def test_naive_datetime_is_rejected() -> None:
    with pytest.raises(ValidationError):
        make_input(now=datetime(2026, 8, 20, 12))


def test_negative_minutes_are_rejected() -> None:
    with pytest.raises(ValidationError):
        make_input(arrival_buffer_minutes=-1)


def test_depart_at_without_fixed_time_is_rejected() -> None:
    with pytest.raises(ValidationError):
        EventSnapshot(
            starts_at=datetime(2026, 8, 20, 14, tzinfo=SEOUL),
            anchor_mode="depart_at",
        )


def test_same_input_has_same_output() -> None:
    plan_input = make_input(environment=EnvironmentSnapshot(precipitation_probability=70))

    assert compute_plan(plan_input) == compute_plan(plan_input)


def test_calc_version_is_reported() -> None:
    result = compute_plan(make_input())

    assert result.calc_version == CALC_VERSION
    assert result.calc_version


def test_partial_config_marks_config_fallback() -> None:
    """Omitting config the engine reads must degrade the result."""
    partial = EngineConfig.model_validate({"rainThresholdPercent": 60})
    result = compute_plan(make_input(config=partial))

    assert DegradedReason.CONFIG_FALLBACK in result.degraded


def test_unused_config_defaults_do_not_degrade() -> None:
    """The buffer defaults never take part in the calculation."""
    config = EngineConfig.model_validate(
        {
            "seedFallbackMinutes": 30,
            "rainThresholdPercent": 60,
            "rainExtraPrepMinutes": 5,
        }
    )
    result = compute_plan(make_input(config=config))

    assert DegradedReason.CONFIG_FALLBACK not in result.degraded
    assert result.prediction_confidence is PredictionConfidence.MID


def test_unknown_input_field_is_rejected() -> None:
    with pytest.raises(ValidationError):
        make_input(unexpectedField="value")
