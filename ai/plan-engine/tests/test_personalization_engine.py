"""Unit tests for the individual rules of the personalization engine (TRD §6).

The golden fixtures pin whole responses; these tests pin one rule each so a
failure says which rule broke.
"""

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pytest

from app.contracts.common import AdjustmentKnob, DelayCause
from app.contracts.personalization import PersonalizationInput
from app.domain.personalization_engine.adjustment import effective_alpha, resolve_seed
from app.domain.personalization_engine.engine import adjust
from app.domain.personalization_engine.enums import (
    ExclusionReason,
    PersonalizationDegraded,
    parse_arrival_result,
)

SEOUL = ZoneInfo("Asia/Seoul")
PREP_START = datetime(2026, 8, 20, 7, 0, tzinfo=SEOUL)
FIXED_PREP_MINUTES = 10
TRAVEL_MINUTES = 40

FULL_CONFIG = {
    "prepEmaAlpha": 0.3,
    "lateWeight": 1.5,
    "earlyWeight": 0.7,
    "maxStepMinutes": 15,
    "coldStepMinutes": 20,
    "prepFloorMinutes": 10,
    "prepCeilingRatio": 2.0,
    "seedFallbackMinutes": 30,
    "coldStartSampleThreshold": 3,
    "clockSkewToleranceSeconds": 120,
    "prepOutlierMaxMinutes": 240,
    "geoMinConfidence": 0.6,
    "attributionMinSignalMinutes": 3,
    "modelVersion": "v1",
}


def build(
    *,
    estimate: float = 30.0,
    sample_count: int = 12,
    start_delay: int = 0,
    prep_duration: int | None = None,
    lingering: int = 0,
    transit_error: int = 0,
    arrival_result: str = "on_time",
    include_prep_finished: bool = True,
    include_arrival: bool = True,
    include_departure: bool = True,
    seed_minutes: float | None = 30.0,
    result_source: str = "user",
    result_confidence: float | None = None,
    clock_skew_seconds: int | None = None,
    auto_manage_excluded: bool = False,
    config: dict | None = None,
) -> PersonalizationInput:
    planned_estimate = int(round(estimate))
    window = planned_estimate + FIXED_PREP_MINUTES
    if prep_duration is None:
        prep_duration = window

    recommended_depart_at = PREP_START + timedelta(minutes=window)
    started_at = PREP_START + timedelta(minutes=start_delay)
    finished_at = started_at + timedelta(minutes=prep_duration)
    departed_at = max(finished_at, recommended_depart_at) + timedelta(minutes=lingering)
    arrived_at = departed_at + timedelta(minutes=TRAVEL_MINUTES + transit_error)

    actual: dict[str, object] = {"actualPrepStartedAt": started_at.isoformat()}
    if include_prep_finished:
        actual["actualPrepFinishedAt"] = finished_at.isoformat()
    if include_departure:
        actual["actualDepartedAt"] = departed_at.isoformat()
    if include_arrival:
        actual["actualArrivedAt"] = arrived_at.isoformat()
    actual["resultSource"] = result_source
    if result_confidence is not None:
        actual["resultConfidence"] = result_confidence
    if clock_skew_seconds is not None:
        actual["clockSkewSeconds"] = clock_skew_seconds

    estimate_payload: dict[str, object] = {
        "estimatedMinutes": estimate,
        "sampleCount": sample_count,
        "modelVersion": "v1",
    }
    if seed_minutes is not None:
        estimate_payload["seedMinutes"] = seed_minutes

    return PersonalizationInput.model_validate(
        {
            "eventId": "evt-unit",
            "planned": {
                "prepStartAt": PREP_START.isoformat(),
                "recommendedDepartAt": recommended_depart_at.isoformat(),
                "targetArriveAt": (
                    recommended_depart_at + timedelta(minutes=TRAVEL_MINUTES)
                ).isoformat(),
                "estimatedPrepMinutes": planned_estimate,
                "travelMinutes": TRAVEL_MINUTES,
                "trafficBufferMinutes": 5,
            },
            "actual": actual,
            "outcome": {
                "arrivalResult": arrival_result,
                "autoManageExcluded": auto_manage_excluded,
            },
            "currentEstimate": estimate_payload,
            "config": config or FULL_CONFIG,
        }
    )


# ──────────────────────────────────────────────────────────────────────────────
# Learning sample qualification (§6.1)
# ──────────────────────────────────────────────────────────────────────────────


class TestEligibility:
    def test_clock_skew_beyond_tolerance_excludes(self):
        result = adjust(build(clock_skew_seconds=121, prep_duration=50))
        assert result.excluded_from_learning is True
        assert ExclusionReason.CLOCK_SKEW.value in result.exclusion_reasons

    def test_clock_skew_within_tolerance_is_kept(self):
        result = adjust(build(clock_skew_seconds=120, prep_duration=50))
        assert result.excluded_from_learning is False

    def test_prep_duration_outlier_excludes(self):
        result = adjust(build(prep_duration=241))
        assert ExclusionReason.PREP_DURATION_OUTLIER.value in result.exclusion_reasons

    def test_geo_result_without_confidence_excludes(self):
        result = adjust(build(result_source="geo", prep_duration=50))
        assert ExclusionReason.GEO_CONFIDENCE_LOW.value in result.exclusion_reasons

    def test_geo_result_below_bar_excludes(self):
        result = adjust(build(result_source="geo", result_confidence=0.59, prep_duration=50))
        assert ExclusionReason.GEO_CONFIDENCE_LOW.value in result.exclusion_reasons

    def test_geo_result_at_bar_is_kept(self):
        result = adjust(build(result_source="geo", result_confidence=0.60, prep_duration=50))
        assert result.excluded_from_learning is False

    def test_unknown_arrival_result_excludes(self):
        result = adjust(build(arrival_result="unknown", prep_duration=50))
        assert ExclusionReason.ARRIVAL_RESULT_UNKNOWN.value in result.exclusion_reasons

    def test_unrecognised_arrival_result_is_treated_as_unknown(self):
        result = adjust(build(arrival_result="whatever", prep_duration=50))
        assert ExclusionReason.ARRIVAL_RESULT_UNKNOWN.value in result.exclusion_reasons

    def test_auto_manage_excluded_excludes(self):
        result = adjust(build(auto_manage_excluded=True, prep_duration=50))
        assert ExclusionReason.AUTO_MANAGE_EXCLUDED.value in result.exclusion_reasons

    def test_excluded_sample_never_moves_a_knob(self):
        result = adjust(build(prep_duration=241, start_delay=30))
        assert result.adjusted_knob is AdjustmentKnob.NONE
        assert result.new_value is None


# ──────────────────────────────────────────────────────────────────────────────
# Cause separation (§6.2 · TR-05)
# ──────────────────────────────────────────────────────────────────────────────


class TestAttribution:
    def test_signal_below_the_noise_floor_is_not_a_cause(self):
        result = adjust(build(start_delay=2, transit_error=2, prep_duration=42))
        # 2-minute signals are noise; the 2-minute overrun is too.
        assert result.cause is DelayCause.UNKNOWN
        assert result.candidates == []

    def test_no_delay_still_refines_the_estimate(self):
        # 2 minutes over the allotted window is below the noise floor, so there
        # is no cause to attribute — but it is still a clean measurement.
        result = adjust(build(prep_duration=42))
        assert result.cause is DelayCause.UNKNOWN
        assert result.adjusted_knob is AdjustmentKnob.PREP_ESTIMATE
        assert result.new_value is not None
        assert result.new_value > 30.0

    def test_observation_matching_the_plan_changes_nothing(self):
        result = adjust(build())
        assert result.cause is DelayCause.UNKNOWN
        assert result.adjusted_knob is AdjustmentKnob.NONE
        assert result.previous_value == result.new_value == 30.0

    def test_multiple_causes_are_reported_but_one_knob_moves(self):
        result = adjust(build(start_delay=10, prep_duration=60, transit_error=20))
        causes = {candidate.cause for candidate in result.candidates}
        assert len(causes) >= 2
        assert result.adjusted_knob is not AdjustmentKnob.NONE
        # Confidence shares of the reported candidates sum to 1.
        assert round(sum(c.confidence for c in result.candidates), 2) == 1.0

    def test_lingering_needs_prep_finished_else_it_looks_like_overrun(self):
        """§6.2 — with three timestamps the two causes are indistinguishable."""
        with_finish = adjust(build(lingering=20, prep_duration=40))
        assert with_finish.cause is DelayCause.DEPART_LATE
        assert PersonalizationDegraded.PREP_FINISH_UNKNOWN.value not in with_finish.degraded

        without_finish = adjust(build(lingering=20, prep_duration=40, include_prep_finished=False))
        assert without_finish.cause is DelayCause.PREP_OVERRUN
        assert PersonalizationDegraded.PREP_FINISH_UNKNOWN.value in without_finish.degraded

    def test_missing_arrival_records_transit_unknown(self):
        result = adjust(build(prep_duration=50, include_arrival=False))
        assert PersonalizationDegraded.TRANSIT_UNKNOWN.value in result.degraded

    def test_missing_departure_is_incomplete(self):
        result = adjust(build(include_departure=False))
        assert ExclusionReason.INCOMPLETE_TIMESTAMPS.value in result.exclusion_reasons


# ──────────────────────────────────────────────────────────────────────────────
# EMA and guard-rails (§6.2)
# ──────────────────────────────────────────────────────────────────────────────


class TestAdjustment:
    def test_late_weight_moves_further_than_on_time(self):
        on_time = adjust(build(prep_duration=60, arrival_result="on_time"))
        late = adjust(build(prep_duration=60, arrival_result="late"))
        assert on_time.new_value is not None
        assert late.new_value is not None
        assert late.new_value > on_time.new_value

    def test_early_weight_shrinks_more_carefully_than_on_time(self):
        """Reducing the estimate must be the weakest signal (§6.2 비대칭)."""
        on_time = adjust(build(prep_duration=20, arrival_result="on_time"))
        early = adjust(build(prep_duration=20, arrival_result="early"))
        assert on_time.new_value is not None
        assert early.new_value is not None
        assert early.new_value > on_time.new_value  # shrank less

    def test_floor_is_never_breached(self):
        result = adjust(build(estimate=12.0, prep_duration=11, arrival_result="early"))
        assert result.new_value is not None
        assert result.new_value >= 10.0

    def test_missing_seed_falls_back_and_is_recorded(self):
        result = adjust(
            build(seed_minutes=None, prep_duration=90, estimate=30.0, arrival_result="late")
        )
        assert PersonalizationDegraded.SEED_FALLBACK.value in result.degraded
        assert result.new_value is not None
        # Ceiling from the fallback seed: 30 × 2.
        assert result.new_value <= 60.0

    def test_step_limit_bounds_one_observation(self):
        result = adjust(build(estimate=20.0, prep_duration=200, arrival_result="late"))
        assert result.previous_value is not None
        assert result.new_value is not None
        assert result.new_value - result.previous_value <= 15.0
        assert PersonalizationDegraded.STEP_LIMITED.value in result.degraded

    def test_config_fallback_is_recorded_when_keys_are_omitted(self):
        result = adjust(build(prep_duration=50, config={"modelVersion": "v1"}))
        assert PersonalizationDegraded.CONFIG_FALLBACK.value in result.degraded

    def test_traffic_buffer_uses_the_same_step_limit(self):
        result = adjust(build(transit_error=90, arrival_result="late", prep_duration=40))
        assert result.cause is DelayCause.TRAFFIC
        assert result.adjusted_knob is AdjustmentKnob.TRAFFIC_BUFFER
        assert result.previous_value == 5.0
        assert result.new_value is not None
        assert result.new_value - result.previous_value <= 15.0


# ──────────────────────────────────────────────────────────────────────────────
# Helpers
# ──────────────────────────────────────────────────────────────────────────────


class TestHelpers:
    @pytest.mark.parametrize(
        ("raw", "expected"),
        [
            ("late", "late"),
            ("ON_TIME", "on_time"),
            (" early ", "early"),
            ("nonsense", "unknown"),
            (None, "unknown"),
        ],
    )
    def test_parse_arrival_result(self, raw, expected):
        assert parse_arrival_result(raw).value == expected

    def test_effective_alpha_is_capped_at_one(self):
        payload = build(prep_duration=50)
        config = payload.config.model_copy(update={"prep_ema_alpha": 0.9, "late_weight": 3.0})
        assert effective_alpha(parse_arrival_result("late"), config) == 1.0

    def test_resolve_seed_prefers_the_user_seed(self):
        payload = build(prep_duration=50, seed_minutes=45.0)
        seed, degraded = resolve_seed(payload.current_estimate, payload.config)
        assert seed == 45.0
        assert degraded == ()
