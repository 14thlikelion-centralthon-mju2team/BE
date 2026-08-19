"""Golden tests for the wellness engine (TRD §17.2 골든 07~09 + 확장).

Fixtures live in three directories, one per entry point:

    tests/golden/wellness/evaluate/   WIS · 행동 · 게이트
    tests/golden/wellness/rush_load/  RLS
    tests/golden/wellness/daily/      DWL · 마무리 카드

A fixture that omits ``input.config`` gets the full appendix A default config
injected, so the case reads as the rule it is checking instead of forty
parameters.  A fixture that needs a non-default weight sets ``config`` itself.

Two invariants are asserted on every evaluate fixture regardless of its
expectations:

* ERD ``ck_wellness_rank`` — at most 3 actions, ranks 1..n without gaps.
* §17.3 ⑥ — WIS below the event threshold can never arm a push.
"""

from pathlib import Path
from typing import Any

import pytest

from app.contracts.config import WellnessEngineConfig
from app.contracts.fixtures import load_fixture
from app.contracts.wellness import (
    MAX_WELLNESS_ACTIONS,
    DailySummaryInput,
    RushLoadInput,
    WellnessInput,
)
from app.domain.wellness_engine.engine import (
    compute_rush_load,
    evaluate_wellness,
    summarize_day,
)
from app.domain.wellness_engine.version import WEIGHT_VERSION

GOLDEN_ROOT = Path(__file__).parent / "golden" / "wellness"
EVALUATE_FIXTURES = sorted((GOLDEN_ROOT / "evaluate").glob("*.json"))
RUSH_LOAD_FIXTURES = sorted((GOLDEN_ROOT / "rush_load").glob("*.json"))
DAILY_FIXTURES = sorted((GOLDEN_ROOT / "daily").glob("*.json"))

#: Every field explicitly set, so no fixture reports ``config_fallback``.
FULL_CONFIG: dict[str, Any] = WellnessEngineConfig(
    weight_version="golden-w1"
).model_dump(by_alias=True)


def prepared_input(fixture_input: dict[str, Any]) -> dict[str, Any]:
    payload = dict(fixture_input)
    payload.setdefault("config", FULL_CONFIG)
    return payload


def test_golden_dirs_are_populated() -> None:
    assert EVALUATE_FIXTURES, "no evaluate fixtures"
    assert RUSH_LOAD_FIXTURES, "no rush_load fixtures"
    assert DAILY_FIXTURES, "no daily fixtures"


# ──────────────────────────────────────────────────────────────────────────────
# evaluate
# ──────────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("fixture_path", EVALUATE_FIXTURES, ids=lambda path: path.stem)
def test_evaluate_golden(fixture_path: Path) -> None:
    fixture = load_fixture(fixture_path)
    assert fixture.engine == "wellness"
    assert fixture.algorithm_version == WEIGHT_VERSION, (
        "fixture was recorded by a different weight version — bump or refresh"
    )

    result = evaluate_wellness(WellnessInput.model_validate(prepared_input(fixture.input)))
    expected = fixture.expected

    assert result.wis_score == expected["wisScore"]
    assert (result.wis_band.value if result.wis_band else None) == expected["wisBand"]
    assert result.event_armed is expected["eventArmed"]
    assert result.armed_action_code == expected["armedActionCode"]
    assert result.arming_blocked_by == expected["armingBlockedBy"]
    assert result.degraded == expected["degraded"]
    assert [action.action_code for action in result.actions] == expected["actionCodes"]

    if "quantized" in expected:
        quantized = (
            result.quantized.model_dump(by_alias=True) if result.quantized else None
        )
        assert quantized == expected["quantized"]
    if "normalizedLoads" in expected:
        loads = (
            result.normalized_loads.model_dump(by_alias=True)
            if result.normalized_loads
            else None
        )
        assert loads == expected["normalizedLoads"]
    if "mergedItemIds" in expected:
        assert [action.merged_item_id for action in result.actions] == expected[
            "mergedItemIds"
        ]
    if "reasonContains" in expected:
        assert any(
            expected["reasonContains"] in action.reason for action in result.actions
        )


@pytest.mark.parametrize("fixture_path", EVALUATE_FIXTURES, ids=lambda path: path.stem)
def test_action_ranks_are_contiguous_and_capped(fixture_path: Path) -> None:
    """ERD ``ck_wellness_rank`` — 1~3, no gaps, no duplicates."""
    fixture = load_fixture(fixture_path)
    result = evaluate_wellness(WellnessInput.model_validate(prepared_input(fixture.input)))

    assert len(result.actions) <= MAX_WELLNESS_ACTIONS
    ranks = [action.display_rank for action in result.actions]
    assert ranks == list(range(1, len(ranks) + 1))


@pytest.mark.parametrize("fixture_path", EVALUATE_FIXTURES, ids=lambda path: path.stem)
def test_low_score_never_arms(fixture_path: Path) -> None:
    """§17.3 ⑥ — wisScore < 70 이면 웰니스 푸시 후보가 절대 생성되지 않는다 (TR-11)."""
    fixture = load_fixture(fixture_path)
    payload = WellnessInput.model_validate(prepared_input(fixture.input))
    result = evaluate_wellness(payload)

    if result.wis_score is None or result.wis_score < payload.config.wellness_event_min:
        assert result.event_armed is False
        assert result.armed_action_code is None


@pytest.mark.parametrize("fixture_path", EVALUATE_FIXTURES, ids=lambda path: path.stem)
def test_evaluate_is_pure(fixture_path: Path) -> None:
    """§17.3 ⑤ — 동일 입력 재실행 시 완전히 동일한 응답."""
    fixture = load_fixture(fixture_path)
    payload = WellnessInput.model_validate(prepared_input(fixture.input))
    assert evaluate_wellness(payload).model_dump() == evaluate_wellness(payload).model_dump()


# ──────────────────────────────────────────────────────────────────────────────
# rush load (RLS)
# ──────────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("fixture_path", RUSH_LOAD_FIXTURES, ids=lambda path: path.stem)
def test_rush_load_golden(fixture_path: Path) -> None:
    fixture = load_fixture(fixture_path)
    result = compute_rush_load(RushLoadInput.model_validate(prepared_input(fixture.input)))
    expected = fixture.expected

    assert result.rush_load_score == expected["rushLoadScore"]
    assert result.prep_delay_norm == expected["prepDelayNorm"]
    assert result.depart_delay_norm == expected["departDelayNorm"]
    assert result.critical_alert_norm == expected["criticalAlertNorm"]


# ──────────────────────────────────────────────────────────────────────────────
# daily summary (DWL)
# ──────────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("fixture_path", DAILY_FIXTURES, ids=lambda path: path.stem)
def test_daily_summary_golden(fixture_path: Path) -> None:
    fixture = load_fixture(fixture_path)
    result = summarize_day(DailySummaryInput.model_validate(prepared_input(fixture.input)))
    expected = fixture.expected

    assert result.event_count == expected["eventCount"]
    assert result.dwl_score == expected["dwlScore"]
    assert (result.dwl_band.value if result.dwl_band else None) == expected["dwlBand"]
    assert result.card_scenario == expected["cardScenario"]
    assert result.card_visible is expected["cardVisible"]
    assert result.degraded == expected["degraded"]
    assert result.total_outdoor_minutes == expected["totalOutdoorMinutes"]
    assert result.avg_wis_weighted == expected["avgWisWeighted"]
    assert result.avg_rls == expected["avgRls"]

    if "cardMessage" in expected:
        assert result.card_message == expected["cardMessage"]


def test_contract_smoke_fixture_matches_the_engine() -> None:
    """The M0 smoke fixture now carries real expectations, so it must be run.

    It deliberately sends the M0-era payload — no ``uvIndex``, no ``airGrade``,
    minimal config — which is what an early Backend integration looks like.  The
    engine answers with a score built from what it does have and records the
    rest in ``degraded``.
    """
    smoke_path = Path(__file__).parent / "fixtures" / "contract_smoke_wellness.json"
    fixture = load_fixture(smoke_path)
    # No config injection here: the point is that a partial config still works.
    result = evaluate_wellness(WellnessInput.model_validate(fixture.input))

    assert result.wis_score == fixture.expected["wisScore"]
    assert result.wis_band is not None
    assert result.wis_band.value == fixture.expected["wisBand"]
    assert [action.action_code for action in result.actions] == fixture.expected["actionCodes"]
    assert result.event_armed is fixture.expected["eventArmed"]
    assert result.armed_action_code == fixture.expected["armedActionCode"]
    assert result.arming_blocked_by == fixture.expected["armingBlockedBy"]
    assert result.degraded == fixture.expected["degraded"]
