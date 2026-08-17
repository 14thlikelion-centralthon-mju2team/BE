"""Checklist merge behaviour and its consistency with the time breakdown."""

from app.domain.plan_engine.engine import compute_plan
from app.domain.plan_engine.enums import PrepActionType, PrepSourceType
from app.schemas.plan import EnvironmentSnapshot, PrepItemSnapshot
from tests.conftest import make_input


def item(
    name: str,
    action_type: PrepActionType,
    source_type: PrepSourceType = PrepSourceType.RULE,
    minutes: int = 0,
    *,
    item_id: str = "item",
    sensitive: bool = False,
) -> PrepItemSnapshot:
    return PrepItemSnapshot(
        item_id=item_id,
        item_name=name,
        action_type=action_type,
        source_type=source_type,
        applied_minutes=minutes,
        is_sensitive=sensitive,
    )


def test_carry_never_reports_routine_minutes() -> None:
    """A carry item must not inherit minutes from a same-named routine."""
    result = compute_plan(
        make_input(
            prep_items=[
                item("우산", PrepActionType.CARRY, PrepSourceType.RULE, 0, item_id="a"),
                item(
                    "우산",
                    PrepActionType.CONSUME,
                    PrepSourceType.EVENT_ITEM,
                    30,
                    item_id="b",
                ),
            ]
        )
    )

    assert [(i.item_name, i.action_type, i.applied_minutes) for i in result.checklist] == [
        ("우산", PrepActionType.CARRY, 0)
    ]
    assert result.breakdown.personal_routine_minutes == 0


def test_same_name_routine_is_summed_and_stays_consistent() -> None:
    result = compute_plan(
        make_input(
            prep_items=[
                item("화장", PrepActionType.TIMED_ROUTINE, minutes=10, item_id="a"),
                item("화장", PrepActionType.TIMED_ROUTINE, minutes=10, item_id="b"),
            ]
        )
    )

    assert len(result.checklist) == 1
    assert result.checklist[0].applied_minutes == 20
    assert result.breakdown.personal_routine_minutes == 20
    assert sum(i.applied_minutes for i in result.checklist) == 20


def test_routine_merged_with_carry_reports_time_once() -> None:
    """Mixed action types keep one entry whose minutes match the breakdown."""
    result = compute_plan(
        make_input(
            prep_items=[
                item("세안", PrepActionType.CARRY, PrepSourceType.RULE, 0, item_id="a"),
                item(
                    "세안",
                    PrepActionType.TIMED_ROUTINE,
                    PrepSourceType.EVENT_ITEM,
                    15,
                    item_id="b",
                ),
            ]
        )
    )

    entry = result.checklist[0]
    assert entry.action_type is PrepActionType.TIMED_ROUTINE
    assert entry.source_type is PrepSourceType.RULE
    assert entry.applied_minutes == 15
    assert result.breakdown.personal_routine_minutes == 15


def test_sensitive_flag_survives_merge() -> None:
    result = compute_plan(
        make_input(
            prep_items=[
                item("복용약", PrepActionType.CONSUME, PrepSourceType.RULE, item_id="a"),
                item(
                    "복용약",
                    PrepActionType.CONSUME,
                    PrepSourceType.EVENT_ITEM,
                    sensitive=True,
                    item_id="b",
                ),
            ]
        )
    )

    assert result.checklist[0].is_sensitive is True


def test_spacing_variants_are_not_guessed_as_the_same_item() -> None:
    """Normalization trims and collapses spaces but never infers meaning."""
    result = compute_plan(
        make_input(
            environment=EnvironmentSnapshot(precipitation_probability=70),
            prep_items=[item("우 산", PrepActionType.CARRY, item_id="a")],
        )
    )

    assert [i.item_name for i in result.checklist] == ["우 산", "우산"]
