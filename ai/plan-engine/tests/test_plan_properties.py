from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from hypothesis import given
from hypothesis import strategies as st

from app.domain.plan_engine.engine import compute_plan
from app.domain.plan_engine.enums import PrepActionType, PrepSourceType
from app.schemas.plan import (
    EngineConfig,
    EventSnapshot,
    PlanInput,
    PrepEstimate,
    PrepItemSnapshot,
    RouteSnapshot,
)

SEOUL = ZoneInfo("Asia/Seoul")


def explicit_config() -> EngineConfig:
    return EngineConfig(
        seed_fallback_minutes=30,
        rain_threshold_percent=60,
        rain_extra_prep_minutes=5,
        arrival_buffer_default_minutes=10,
        traffic_buffer_default_minutes=5,
    )


@st.composite
def plan_inputs(draw: st.DrawFn) -> PlanInput:
    now = datetime(2026, 8, 20, 9, tzinfo=SEOUL)
    event_start = now + timedelta(minutes=draw(st.integers(min_value=60, max_value=1440)))
    return PlanInput(
        now=now,
        event=EventSnapshot(starts_at=event_start, anchor_mode="arrive_by"),
        prep_estimate=PrepEstimate(
            estimated_minutes=draw(st.integers(min_value=0, max_value=180)),
            source="property",
            sample_count=draw(st.integers(min_value=0, max_value=20)),
        ),
        arrival_buffer_minutes=draw(st.integers(min_value=0, max_value=60)),
        traffic_buffer_minutes=draw(st.integers(min_value=0, max_value=60)),
        selected_route=RouteSnapshot(
            route_id="route",
            total_minutes=draw(st.integers(min_value=0, max_value=180)),
            source="property",
        ),
        environment=None,
        prep_items=[],
        config=explicit_config(),
    )


@given(plan_inputs())
def test_time_order_invariants(plan_input: PlanInput) -> None:
    result = compute_plan(plan_input)

    assert result.prep_start_at <= result.recommended_depart_at
    assert result.recommended_depart_at <= result.target_arrive_at
    assert result.target_arrive_at <= plan_input.event.starts_at


@given(plan_inputs(), st.integers(min_value=0, max_value=120))
def test_timed_routine_moves_prep_start_exactly(plan_input: PlanInput, minutes: int) -> None:
    base = compute_plan(plan_input)
    routine = PrepItemSnapshot(
        item_id="routine",
        item_name="루틴",
        action_type=PrepActionType.TIMED_ROUTINE,
        source_type=PrepSourceType.RULE,
        applied_minutes=minutes,
    )
    with_routine = compute_plan(plan_input.model_copy(update={"prep_items": [routine]}))

    assert with_routine.prep_start_at == base.prep_start_at - timedelta(minutes=minutes)


@given(
    plan_inputs(),
    st.sampled_from(
        [PrepActionType.CARRY, PrepActionType.CONSUME, PrepActionType.PURCHASE]
    ),
)
def test_non_timed_items_do_not_move_prep_start(
    plan_input: PlanInput, action_type: PrepActionType
) -> None:
    base = compute_plan(plan_input)
    item = PrepItemSnapshot(
        item_id="item",
        item_name="항목",
        action_type=action_type,
        source_type=PrepSourceType.RULE,
        applied_minutes=120,
    )
    with_item = compute_plan(plan_input.model_copy(update={"prep_items": [item]}))

    assert with_item.prep_start_at == base.prep_start_at


@given(plan_inputs())
def test_same_input_is_deterministic(plan_input: PlanInput) -> None:
    assert compute_plan(plan_input) == compute_plan(plan_input)


@given(plan_inputs())
def test_environment_absence_still_returns_time_plan(plan_input: PlanInput) -> None:
    result = compute_plan(plan_input)

    assert result.prep_start_at is not None
    assert result.recommended_depart_at is not None
    assert result.target_arrive_at is not None


@given(plan_inputs())
def test_breakdown_reconstructs_prep_start(plan_input: PlanInput) -> None:
    result = compute_plan(plan_input)

    assert result.prep_start_at == result.recommended_depart_at - timedelta(
        minutes=result.breakdown.estimated_prep_minutes
        + result.breakdown.extra_prep_minutes
        + result.breakdown.personal_routine_minutes
    )


@given(
    plan_inputs(),
    st.lists(
        st.tuples(
            st.sampled_from(["렌즈", "화장", "식사"]),
            st.sampled_from(list(PrepActionType)),
            st.integers(min_value=0, max_value=60),
        ),
        max_size=6,
    ),
)
def test_checklist_minutes_always_match_breakdown(
    plan_input: PlanInput, raw_items: list[tuple[str, PrepActionType, int]]
) -> None:
    items = [
        PrepItemSnapshot(
            item_id=f"item-{index}",
            item_name=name,
            action_type=action_type,
            source_type=PrepSourceType.RULE,
            applied_minutes=minutes,
        )
        for index, (name, action_type, minutes) in enumerate(raw_items)
    ]
    result = compute_plan(plan_input.model_copy(update={"prep_items": items}))

    assert sum(item.applied_minutes for item in result.checklist) == (
        result.breakdown.personal_routine_minutes
    )
    for item in result.checklist:
        if item.action_type is not PrepActionType.TIMED_ROUTINE:
            assert item.applied_minutes == 0
