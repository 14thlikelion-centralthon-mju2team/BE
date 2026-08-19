"""지표 산출 정의 검증 (TRD §16.2 · §18 M4).

집계는 Postgres 주간 집계 뷰에서 일어납니다. 여기 벡터는 **SQL이 같은 정의를 재현하는지**
확인하는 기준입니다. 기대값은 손으로 계산했습니다.
"""

import json
from pathlib import Path
from typing import Any

import pytest

from app.domain.metrics.north_star import (
    REASON_ALERTS,
    REASON_ARRIVAL,
    REASON_DEPART,
    REASON_EARLY,
    REASON_MARGIN_UNKNOWN,
    REASON_RUSHED,
    NorthStarConfig,
    NorthStarInput,
    aggregate_north_star,
    evaluate_north_star,
)
from app.domain.metrics.wellness_metrics import (
    WellnessMetricInput,
    compute_wellness_metrics,
)

VECTOR_DIR = Path(__file__).parent / "golden" / "metrics"
CONFIG = NorthStarConfig()


def row(**overrides: Any) -> NorthStarInput:
    payload: dict[str, Any] = {
        "eventId": "e1",
        "arrivalResult": "on_time",
        "criticalAlertCount": 0,
        "departDeltaMinutes": 0.0,
        "rushAssessment": "appropriate",
        "marginMinutes": 10.0,
    }
    payload.update(overrides)
    return NorthStarInput.model_validate(payload)


# ──────────────────────────────────────────────────────────────────────────────
# 적합성 벡터
# ──────────────────────────────────────────────────────────────────────────────


def test_north_star_vector() -> None:
    vector: dict[str, Any] = json.loads(
        (VECTOR_DIR / "M01_north_star_week.json").read_text(encoding="utf-8")
    )
    rows = [NorthStarInput.model_validate(item) for item in vector["input"]["rows"]]
    config = NorthStarConfig.model_validate(vector["input"]["config"])
    result = aggregate_north_star(rows, config)
    expected = vector["expected"]

    assert result.total_events == expected["totalEvents"]
    assert result.ok_events == expected["okEvents"]
    assert result.ok_ratio == pytest.approx(expected["okRatio"], abs=1e-4)
    assert result.failed_by_reason == expected["failedByReason"]


def test_wellness_metrics_vector() -> None:
    vector: dict[str, Any] = json.loads(
        (VECTOR_DIR / "M02_wellness_metrics_week.json").read_text(encoding="utf-8")
    )
    counts = WellnessMetricInput.model_validate(vector["input"]["counts"])
    result = compute_wellness_metrics(counts)
    expected = vector["expected"]

    assert result.action_completion_rate == pytest.approx(expected["actionCompletionRate"])
    assert result.event_response_rate == pytest.approx(expected["eventResponseRate"])
    assert result.usefulness_rate == pytest.approx(expected["usefulnessRate"])
    assert result.coverage_rate == pytest.approx(expected["coverageRate"])


# ──────────────────────────────────────────────────────────────────────────────
# 북극성 — 다섯 조건 (§16.2)
# ──────────────────────────────────────────────────────────────────────────────


class TestNorthStarConditions:
    def test_all_conditions_met(self) -> None:
        assert evaluate_north_star(row(), CONFIG).ok is True

    def test_late_arrival_fails(self) -> None:
        outcome = evaluate_north_star(row(arrivalResult="late"), CONFIG)
        assert outcome.ok is False
        assert REASON_ARRIVAL in outcome.failed_reasons

    def test_alert_limit_is_inclusive(self) -> None:
        """극한 알림 ≤ 1 — 1회는 통과, 2회는 실패."""
        assert evaluate_north_star(row(criticalAlertCount=1), CONFIG).ok is True
        outcome = evaluate_north_star(row(criticalAlertCount=2), CONFIG)
        assert REASON_ALERTS in outcome.failed_reasons

    @pytest.mark.parametrize("delta", [-10.0, 0.0, 10.0])
    def test_depart_tolerance_is_symmetric_and_inclusive(self, delta: float) -> None:
        assert evaluate_north_star(row(departDeltaMinutes=delta), CONFIG).ok is True

    @pytest.mark.parametrize("delta", [-10.1, 10.1])
    def test_depart_outside_tolerance_fails(self, delta: float) -> None:
        outcome = evaluate_north_star(row(departDeltaMinutes=delta), CONFIG)
        assert REASON_DEPART in outcome.failed_reasons

    def test_rushed_assessment_fails(self) -> None:
        outcome = evaluate_north_star(row(rushAssessment="rushed"), CONFIG)
        assert REASON_RUSHED in outcome.failed_reasons

    def test_missing_rush_assessment_is_not_a_failure(self) -> None:
        """사용자가 평가하지 않은 것을 촉박함으로 세지 않는다."""
        assert evaluate_north_star(row(rushAssessment=None), CONFIG).ok is True

    def test_early_margin_boundary_is_inclusive(self) -> None:
        """margin ≤ EARLY_MIN(30) — 30분은 통과, 31분은 과도한 조기 도착."""
        assert evaluate_north_star(row(marginMinutes=30.0), CONFIG).ok is True
        outcome = evaluate_north_star(row(marginMinutes=31.0), CONFIG)
        assert REASON_EARLY in outcome.failed_reasons

    def test_arriving_two_hours_early_is_not_success(self) -> None:
        """정시 도착만 세면 두 시간 일찍 앉아 기다린 날도 성공이 된다 (PRD §8.2)."""
        outcome = evaluate_north_star(row(marginMinutes=120.0), CONFIG)
        assert outcome.ok is False
        assert outcome.failed_reasons == (REASON_EARLY,)

    def test_unknown_margin_is_not_counted_as_success(self) -> None:
        outcome = evaluate_north_star(row(marginMinutes=None), CONFIG)
        assert outcome.ok is False
        assert REASON_MARGIN_UNKNOWN in outcome.failed_reasons

    def test_multiple_failures_are_all_reported(self) -> None:
        outcome = evaluate_north_star(
            row(
                arrivalResult="late",
                criticalAlertCount=5,
                departDeltaMinutes=30.0,
                rushAssessment="rushed",
                marginMinutes=90.0,
            ),
            CONFIG,
        )
        assert set(outcome.failed_reasons) == {
            REASON_ARRIVAL,
            REASON_ALERTS,
            REASON_DEPART,
            REASON_RUSHED,
            REASON_EARLY,
        }

    def test_thresholds_are_remote_config(self) -> None:
        """TR-06 — 쿼리를 고치지 않고 임계값만 바꿔 재집계할 수 있어야 한다."""
        strict = NorthStarConfig(depart_tolerance_minutes=2, early_minutes=10)
        assert evaluate_north_star(row(departDeltaMinutes=5.0), CONFIG).ok is True
        assert evaluate_north_star(row(departDeltaMinutes=5.0), strict).ok is False


class TestNorthStarAggregate:
    def test_empty_period_has_no_ratio(self) -> None:
        """0건 중 0건은 0%가 아니라 측정 불가다."""
        aggregate = aggregate_north_star([], CONFIG)
        assert aggregate.total_events == 0
        assert aggregate.ok_ratio is None

    def test_ratio_and_reason_counts(self) -> None:
        aggregate = aggregate_north_star(
            [row(), row(arrivalResult="late"), row(marginMinutes=90.0)], CONFIG
        )
        assert aggregate.ok_events == 1
        assert aggregate.ok_ratio == pytest.approx(1 / 3, abs=1e-4)
        assert aggregate.failed_by_reason == {REASON_ARRIVAL: 1, REASON_EARLY: 1}

    def test_reasons_are_sorted_for_stable_reporting(self) -> None:
        aggregate = aggregate_north_star(
            [row(marginMinutes=90.0), row(arrivalResult="late"), row(criticalAlertCount=9)],
            CONFIG,
        )
        assert list(aggregate.failed_by_reason) == sorted(aggregate.failed_by_reason)


# ──────────────────────────────────────────────────────────────────────────────
# 웰니스 보조 4종 (§16.2)
# ──────────────────────────────────────────────────────────────────────────────


class TestWellnessMetrics:
    def test_all_four_ratios(self) -> None:
        result = compute_wellness_metrics(
            WellnessMetricInput(
                proposed_actions=10,
                completed_actions=4,
                events_sent=8,
                events_completed=3,
                events_snoozed=2,
                ratings_collected=4,
                ratings_useful=3,
                outdoor_events=20,
                wis_generated_events=15,
            )
        )
        assert result.action_completion_rate == pytest.approx(0.4)
        assert result.event_response_rate == pytest.approx(0.625)
        assert result.usefulness_rate == pytest.approx(0.75)
        assert result.coverage_rate == pytest.approx(0.75)

    def test_snoozed_counts_as_a_response(self) -> None:
        """미루기는 무시가 아니라 반응이다 — 알림이 닿았다는 증거다 (§16.2)."""
        result = compute_wellness_metrics(
            WellnessMetricInput(events_sent=4, events_completed=0, events_snoozed=4)
        )
        assert result.event_response_rate == pytest.approx(1.0)

    def test_ignored_is_not_a_response(self) -> None:
        result = compute_wellness_metrics(
            WellnessMetricInput(events_sent=4, events_completed=1, events_snoozed=0)
        )
        assert result.event_response_rate == pytest.approx(0.25)

    def test_zero_denominators_are_unmeasurable_not_zero(self) -> None:
        result = compute_wellness_metrics(WellnessMetricInput())
        assert result.action_completion_rate is None
        assert result.event_response_rate is None
        assert result.usefulness_rate is None
        assert result.coverage_rate is None

    def test_proposing_nothing_differs_from_completing_nothing(self) -> None:
        nothing_proposed = compute_wellness_metrics(WellnessMetricInput(proposed_actions=0))
        nothing_completed = compute_wellness_metrics(
            WellnessMetricInput(proposed_actions=5, completed_actions=0)
        )
        assert nothing_proposed.action_completion_rate is None
        assert nothing_completed.action_completion_rate == 0.0

    def test_coverage_uses_outdoor_events_as_denominator(self) -> None:
        """커버리지 = WIS 생성 일정 / 야외 노출이 있는 일정 (§16.2)."""
        result = compute_wellness_metrics(
            WellnessMetricInput(outdoor_events=4, wis_generated_events=1)
        )
        assert result.coverage_rate == pytest.approx(0.25)
