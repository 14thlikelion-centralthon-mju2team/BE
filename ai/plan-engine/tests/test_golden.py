import json
from datetime import timedelta
from pathlib import Path

import pytest

from app.domain.plan_engine.engine import compute_plan
from app.domain.plan_engine.enums import AnchorMode
from app.schemas.plan import PlanInput

GOLDEN_DIR = Path(__file__).parent / "golden"
GOLDEN_FIXTURES = sorted(GOLDEN_DIR.glob("*.json"))


def load(fixture_path: Path) -> dict[str, object]:
    return json.loads(fixture_path.read_text(encoding="utf-8"))


@pytest.mark.parametrize("fixture_path", GOLDEN_FIXTURES, ids=lambda path: path.stem)
def test_golden_fixture(fixture_path: Path) -> None:
    fixture = load(fixture_path)
    result = compute_plan(PlanInput.model_validate(fixture["input"]))
    expected = fixture["expected"]
    assert isinstance(expected, dict)

    assert result.prep_start_at.isoformat() == expected["prepStartAt"]
    assert result.recommended_depart_at.isoformat() == expected["recommendedDepartAt"]
    assert result.target_arrive_at.isoformat() == expected["targetArriveAt"]
    assert result.feasible is expected["feasible"]
    assert result.breakdown.model_dump(by_alias=True) == expected["breakdown"]
    assert [item.item_name for item in result.checklist] == expected["checklist"]
    assert result.prediction_confidence.value == expected["predictionConfidence"]
    assert [reason.value for reason in result.degraded] == expected["degraded"]
    if "reasonContains" in expected:
        assert any(expected["reasonContains"] in reason.text for reason in result.reasons)


@pytest.mark.parametrize("fixture_path", GOLDEN_FIXTURES, ids=lambda path: path.stem)
def test_breakdown_reconstructs_timestamps(fixture_path: Path) -> None:
    """Every returned timestamp must be explainable by the breakdown alone."""
    plan_input = PlanInput.model_validate(load(fixture_path)["input"])
    result = compute_plan(plan_input)
    breakdown = result.breakdown

    if plan_input.event.anchor_mode is AnchorMode.ARRIVE_BY:
        assert result.target_arrive_at == plan_input.event.starts_at - timedelta(
            minutes=breakdown.arrival_buffer_minutes
        )
        assert result.recommended_depart_at == result.target_arrive_at - timedelta(
            minutes=breakdown.travel_minutes + breakdown.traffic_buffer_minutes
        )
    else:
        assert result.recommended_depart_at == plan_input.event.fixed_depart_at
        assert result.target_arrive_at == result.recommended_depart_at + timedelta(
            minutes=breakdown.travel_minutes
        )
        # A fixed departure ignores both buffers, so they must be reported as 0.
        assert breakdown.traffic_buffer_minutes == 0
        assert breakdown.arrival_buffer_minutes == 0

    assert result.prep_start_at == result.recommended_depart_at - timedelta(
        minutes=breakdown.estimated_prep_minutes
        + breakdown.extra_prep_minutes
        + breakdown.personal_routine_minutes
    )


@pytest.mark.parametrize("fixture_path", GOLDEN_FIXTURES, ids=lambda path: path.stem)
def test_checklist_minutes_match_breakdown(fixture_path: Path) -> None:
    result = compute_plan(PlanInput.model_validate(load(fixture_path)["input"]))

    checklist_minutes = sum(item.applied_minutes for item in result.checklist)
    assert checklist_minutes == result.breakdown.personal_routine_minutes
