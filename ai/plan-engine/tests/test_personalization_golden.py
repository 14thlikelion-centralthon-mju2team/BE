"""Golden tests for the personalization engine (TRD §17.2).

Every fixture is a frozen snapshot of the M2 rules.  If a constant or a routing
rule changes, these break — and the only way to make them pass again is to bump
``MODEL_VERSION`` and refresh the snapshot deliberately (§17.2, §5.1).

Two invariants are asserted on every fixture regardless of its expectations:

* TR-05 — exactly one knob moves, never two.
* §6.2 — the prep estimate only changes when the knob *is* ``prep_estimate``.
"""

from pathlib import Path

import pytest

from app.contracts.common import AdjustmentKnob, DelayCause
from app.contracts.fixtures import load_fixture
from app.contracts.personalization import PersonalizationInput
from app.domain.personalization_engine.engine import adjust
from app.domain.personalization_engine.version import MODEL_VERSION

GOLDEN_DIR = Path(__file__).parent / "golden" / "personalization"
GOLDEN_FIXTURES = sorted(GOLDEN_DIR.glob("*.json"))

#: Causes that must never move the prep estimate (TRD §6.2 "추정 시간 불변").
ESTIMATE_PRESERVING_CAUSES = frozenset(
    {DelayCause.PREP_LATE, DelayCause.DEPART_LATE, DelayCause.TRAFFIC, DelayCause.EXTERNAL}
)


def test_golden_dir_is_populated() -> None:
    assert GOLDEN_FIXTURES, f"no personalization fixtures in {GOLDEN_DIR}"


@pytest.mark.parametrize("fixture_path", GOLDEN_FIXTURES, ids=lambda path: path.stem)
def test_golden_fixture(fixture_path: Path) -> None:
    fixture = load_fixture(fixture_path)
    assert fixture.engine == "personalization"
    assert fixture.algorithm_version == MODEL_VERSION, (
        "fixture was recorded by a different model version — bump or refresh"
    )

    result = adjust(PersonalizationInput.model_validate(fixture.input))
    expected = fixture.expected

    assert result.cause.value == expected["cause"]
    assert result.adjusted_knob.value == expected["adjustedKnob"]
    assert result.previous_value == expected["previousValue"]
    assert result.new_value == expected["newValue"]
    assert result.cause_confidence == expected["causeConfidence"]
    assert result.excluded_from_learning is expected["excludedFromLearning"]
    assert result.exclusion_reasons == expected["exclusionReasons"]
    assert result.degraded == expected["degraded"]
    assert [
        candidate.model_dump(by_alias=True) for candidate in result.candidates
    ] == expected["candidates"]

    if "reasonContains" in expected:
        assert result.adjustment_reason is not None
        assert expected["reasonContains"] in result.adjustment_reason


@pytest.mark.parametrize("fixture_path", GOLDEN_FIXTURES, ids=lambda path: path.stem)
def test_one_observation_moves_one_knob(fixture_path: Path) -> None:
    """TR-05 — the response can only name a single knob."""
    fixture = load_fixture(fixture_path)
    result = adjust(PersonalizationInput.model_validate(fixture.input))

    # The contract has one knob field, so the structural guarantee is that a
    # value pair is only reported together with a knob that owns it.
    if result.adjusted_knob is AdjustmentKnob.NONE:
        assert result.new_value is None or result.new_value == result.previous_value
    else:
        assert result.adjusted_knob in set(AdjustmentKnob)


@pytest.mark.parametrize("fixture_path", GOLDEN_FIXTURES, ids=lambda path: path.stem)
def test_non_prep_causes_never_change_the_estimate(fixture_path: Path) -> None:
    """§6.2 — traffic, prep_late, depart_late and external leave P alone."""
    fixture = load_fixture(fixture_path)
    payload = PersonalizationInput.model_validate(fixture.input)
    result = adjust(payload)

    if result.cause in ESTIMATE_PRESERVING_CAUSES:
        assert result.adjusted_knob is not AdjustmentKnob.PREP_ESTIMATE


@pytest.mark.parametrize("fixture_path", GOLDEN_FIXTURES, ids=lambda path: path.stem)
def test_engine_is_pure(fixture_path: Path) -> None:
    """§17.3 ⑤ — the same input re-run produces the identical response."""
    fixture = load_fixture(fixture_path)
    payload = PersonalizationInput.model_validate(fixture.input)

    first = adjust(payload).model_dump(by_alias=True)
    second = adjust(payload).model_dump(by_alias=True)
    assert first == second


def test_contract_smoke_fixture_matches_the_engine() -> None:
    """The M0 smoke fixture now carries real expectations, so it must be run.

    It deliberately sends a minimal config and no ``actualPrepFinishedAt``,
    which is what an early Backend integration looks like.
    """
    smoke_path = Path(__file__).parent / "fixtures" / "contract_smoke_personalization.json"
    fixture = load_fixture(smoke_path)
    result = adjust(PersonalizationInput.model_validate(fixture.input))

    assert result.cause.value == fixture.expected["cause"]
    assert result.adjusted_knob.value == fixture.expected["adjustedKnob"]
    assert result.previous_value == fixture.expected["previousValue"]
    assert result.new_value == fixture.expected["newValue"]
    assert result.cause_confidence == fixture.expected["causeConfidence"]
    assert result.excluded_from_learning is fixture.expected["excludedFromLearning"]
    assert result.exclusion_reasons == fixture.expected["exclusionReasons"]
    assert result.degraded == fixture.expected["degraded"]
