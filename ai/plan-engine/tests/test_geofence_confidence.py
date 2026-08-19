"""도착 판정 신뢰도 검증·튜닝 (TRD §9.2 · §18 M4).

신뢰도는 판정 결과를 받는 Spring이 계산합니다. 여기 벡터와 진리표는 **Java 구현이 같은 값을
내는지 확인하는 기준**이고, 동시에 계수를 조정할 때 무엇이 어떻게 움직이는지 보여주는 표입니다.

실기기 실측 목표는 자동 확정률 ≥ 70% · 오판 ≤ 10%입니다(§17.4). 아래 진리표는 그 목표를 향해
계수를 움직일 때 어느 조합이 경계를 넘나드는지를 고정해 둡니다.
"""

import json
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import pytest

from app.contracts.config import GeofenceConfig
from app.domain.geofence.confidence import (
    ArrivalObservation,
    compute_arrival_confidence,
    destination_radius_meters,
    is_unresolved_by_timeout,
)
from app.domain.geofence.enums import ArrivalDecision, DestinationKind

SEOUL = ZoneInfo("Asia/Seoul")
EXPECTED_ARRIVAL = datetime(2026, 8, 20, 14, 0, tzinfo=SEOUL)
CONFIG = GeofenceConfig()

VECTOR_DIR = Path(__file__).parent / "golden" / "geofence"
VECTORS = sorted(VECTOR_DIR.glob("*.json"))


def observe(
    *,
    dwell_met: bool,
    accuracy_met: bool,
    timing_met: bool,
    oscillating: bool,
    kind: DestinationKind = DestinationKind.UNKNOWN,
) -> ArrivalObservation:
    return ArrivalObservation(
        dwell_seconds=95.0 if dwell_met else 10.0,
        horizontal_accuracy_meters=20.0 if accuracy_met else 120.0,
        entered_at=EXPECTED_ARRIVAL + timedelta(minutes=5 if timing_met else 60),
        expected_arrival_at=EXPECTED_ARRIVAL,
        transitions_in_window=3 if oscillating else 1,
        destination_kind=kind,
    )


def test_vector_dir_is_populated() -> None:
    assert VECTORS, f"no conformance vectors in {VECTOR_DIR}"


@pytest.mark.parametrize("vector_path", VECTORS, ids=lambda path: path.stem)
def test_conformance_vector(vector_path: Path) -> None:
    vector: dict[str, Any] = json.loads(vector_path.read_text(encoding="utf-8"))
    raw = vector["input"]["observation"]
    observation = ArrivalObservation(
        dwell_seconds=raw["dwellSeconds"],
        horizontal_accuracy_meters=raw["horizontalAccuracyMeters"],
        entered_at=datetime.fromisoformat(raw["enteredAt"]),
        expected_arrival_at=datetime.fromisoformat(raw["expectedArrivalAt"]),
        transitions_in_window=raw["transitionsInWindow"],
        destination_kind=DestinationKind(raw["destinationKind"]),
    )
    config = GeofenceConfig.model_validate(vector["input"]["config"])
    result = compute_arrival_confidence(observation, config)
    expected = vector["expected"]

    assert result.confidence == pytest.approx(expected["confidence"])
    assert result.decision.value == expected["decision"]
    assert result.radius_meters == expected["radiusMeters"]


# ──────────────────────────────────────────────────────────────────────────────
# 진리표 — 가점 3종 × 진동 = 16조합. 기대값은 §9.2 계수로 손으로 계산했다.
# ──────────────────────────────────────────────────────────────────────────────

TRUTH_TABLE: list[tuple[bool, bool, bool, bool, float, ArrivalDecision]] = [
    # dwell, accuracy, timing, oscillating, confidence, decision
    (False, False, False, False, 0.50, ArrivalDecision.QUIET_CONFIRM),
    (True, False, False, False, 0.70, ArrivalDecision.AUTO_CONFIRM),
    (False, True, False, False, 0.65, ArrivalDecision.AUTO_CONFIRM),
    (False, False, True, False, 0.65, ArrivalDecision.AUTO_CONFIRM),
    (True, True, False, False, 0.85, ArrivalDecision.AUTO_CONFIRM),
    (True, False, True, False, 0.85, ArrivalDecision.AUTO_CONFIRM),
    (False, True, True, False, 0.80, ArrivalDecision.AUTO_CONFIRM),
    (True, True, True, False, 1.00, ArrivalDecision.AUTO_CONFIRM),
    (False, False, False, True, 0.20, ArrivalDecision.UNRESOLVED),
    (True, False, False, True, 0.40, ArrivalDecision.QUIET_CONFIRM),
    (False, True, False, True, 0.35, ArrivalDecision.UNRESOLVED),
    (False, False, True, True, 0.35, ArrivalDecision.UNRESOLVED),
    (True, True, False, True, 0.55, ArrivalDecision.QUIET_CONFIRM),
    (True, False, True, True, 0.55, ArrivalDecision.QUIET_CONFIRM),
    (False, True, True, True, 0.50, ArrivalDecision.QUIET_CONFIRM),
    (True, True, True, True, 0.70, ArrivalDecision.AUTO_CONFIRM),
]


@pytest.mark.parametrize(
    ("dwell", "accuracy", "timing", "oscillating", "expected", "decision"),
    TRUTH_TABLE,
)
def test_truth_table(
    dwell: bool,
    accuracy: bool,
    timing: bool,
    oscillating: bool,
    expected: float,
    decision: ArrivalDecision,
) -> None:
    result = compute_arrival_confidence(
        observe(
            dwell_met=dwell,
            accuracy_met=accuracy,
            timing_met=timing,
            oscillating=oscillating,
        ),
        CONFIG,
    )
    assert result.confidence == pytest.approx(expected)
    assert result.decision is decision


class TestBoundaries:
    def test_dwell_boundary_is_inclusive(self) -> None:
        """DWELL_SEC 정확히 90초는 충족으로 본다."""
        at = ArrivalObservation(
            dwell_seconds=90.0,
            horizontal_accuracy_meters=200.0,
            entered_at=EXPECTED_ARRIVAL + timedelta(hours=2),
            expected_arrival_at=EXPECTED_ARRIVAL,
        )
        below = ArrivalObservation(
            dwell_seconds=89.9,
            horizontal_accuracy_meters=200.0,
            entered_at=EXPECTED_ARRIVAL + timedelta(hours=2),
            expected_arrival_at=EXPECTED_ARRIVAL,
        )
        assert compute_arrival_confidence(at, CONFIG).dwell == pytest.approx(0.20)
        assert compute_arrival_confidence(below, CONFIG).dwell == 0.0

    def test_accuracy_boundary_is_exclusive(self) -> None:
        """§9.2는 '< 50m'이므로 50m 정확히는 가점을 받지 못한다."""
        base = dict(
            dwell_seconds=0.0,
            entered_at=EXPECTED_ARRIVAL + timedelta(hours=2),
            expected_arrival_at=EXPECTED_ARRIVAL,
        )
        assert compute_arrival_confidence(
            ArrivalObservation(horizontal_accuracy_meters=49.9, **base), CONFIG
        ).accuracy == pytest.approx(0.15)
        assert (
            compute_arrival_confidence(
                ArrivalObservation(horizontal_accuracy_meters=50.0, **base), CONFIG
            ).accuracy
            == 0.0
        )

    def test_missing_accuracy_gets_no_bonus(self) -> None:
        """기기가 정확도를 주지 않았으면 좋다고 가정하지 않는다."""
        observation = ArrivalObservation(
            dwell_seconds=0.0,
            horizontal_accuracy_meters=None,
            entered_at=EXPECTED_ARRIVAL,
            expected_arrival_at=EXPECTED_ARRIVAL,
        )
        assert compute_arrival_confidence(observation, CONFIG).accuracy == 0.0

    @pytest.mark.parametrize("offset_minutes", [-20, -19, 0, 19, 20])
    def test_timing_window_is_symmetric_and_inclusive(self, offset_minutes: int) -> None:
        observation = ArrivalObservation(
            dwell_seconds=0.0,
            horizontal_accuracy_meters=200.0,
            entered_at=EXPECTED_ARRIVAL + timedelta(minutes=offset_minutes),
            expected_arrival_at=EXPECTED_ARRIVAL,
        )
        assert compute_arrival_confidence(observation, CONFIG).timing == pytest.approx(0.15)

    @pytest.mark.parametrize("offset_minutes", [-21, 21])
    def test_outside_timing_window_gets_no_bonus(self, offset_minutes: int) -> None:
        observation = ArrivalObservation(
            dwell_seconds=0.0,
            horizontal_accuracy_meters=200.0,
            entered_at=EXPECTED_ARRIVAL + timedelta(minutes=offset_minutes),
            expected_arrival_at=EXPECTED_ARRIVAL,
        )
        assert compute_arrival_confidence(observation, CONFIG).timing == 0.0

    def test_single_transition_is_not_oscillation(self) -> None:
        """한 번 들어온 것은 진동이 아니다."""
        observation = observe(
            dwell_met=True, accuracy_met=True, timing_met=True, oscillating=False
        )
        assert compute_arrival_confidence(observation, CONFIG).oscillation == 0.0

    def test_confidence_is_clamped_to_unit_range(self) -> None:
        generous = CONFIG.model_copy(update={"dwell_bonus": 0.9, "accuracy_bonus": 0.9})
        harsh = CONFIG.model_copy(update={"oscillation_penalty": 1.0})
        assert (
            compute_arrival_confidence(
                observe(dwell_met=True, accuracy_met=True, timing_met=True, oscillating=False),
                generous,
            ).confidence
            == 1.0
        )
        assert (
            compute_arrival_confidence(
                observe(
                    dwell_met=False, accuracy_met=False, timing_met=False, oscillating=True
                ),
                harsh,
            ).confidence
            == 0.0
        )


class TestRadius:
    @pytest.mark.parametrize(
        ("kind", "expected"),
        [
            (DestinationKind.GROUND_POI, 100),
            (DestinationKind.SUBWAY_OR_COMPLEX, 200),
            (DestinationKind.UNKNOWN, 150),
        ],
    )
    def test_radius_by_destination_kind(self, kind: DestinationKind, expected: int) -> None:
        assert destination_radius_meters(kind, CONFIG) == expected


class TestUnresolvedTimeout:
    @pytest.mark.parametrize(
        ("minutes", "expected"), [(0, False), (29.9, False), (30, True), (45, True)]
    )
    def test_timeout_boundary(self, minutes: float, expected: bool) -> None:
        assert is_unresolved_by_timeout(minutes, CONFIG) is expected


class TestFeedbackSuppression:
    def test_auto_confirmed_observations_do_not_ask_the_user(self) -> None:
        """§9.3 — confidence ≥ AUTO_CONF 면 피드백 UI 자체를 띄우지 않는다 (PRD §12.10)."""
        result = compute_arrival_confidence(
            observe(dwell_met=True, accuracy_met=True, timing_met=True, oscillating=False),
            CONFIG,
        )
        assert result.auto_confirmed is True

    def test_uncertain_observations_ask_once(self) -> None:
        result = compute_arrival_confidence(
            observe(dwell_met=False, accuracy_met=False, timing_met=False, oscillating=False),
            CONFIG,
        )
        assert result.auto_confirmed is False
        assert result.decision is ArrivalDecision.QUIET_CONFIRM
