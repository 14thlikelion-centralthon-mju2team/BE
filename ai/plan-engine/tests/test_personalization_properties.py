"""Property tests for the correction guard-rails (TRD §17.3 불변식 ①②③).

    ① P ∈ [10, 시드×2] · 1회 변화 ≤ 15분 (콜드 스타트 예외)
    ② 교통 지연만 있는 시퀀스에서 P 는 변하지 않는다   ← 원인 분리의 핵심 (TR-05)
    ③ 전부 on_time 인 시퀀스에서 P 는 발산하지 않는다

Invariant ⑤ (purity) is covered per-fixture in ``test_personalization_golden``.
"""

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from hypothesis import given, settings
from hypothesis import strategies as st

from app.contracts.common import AdjustmentKnob
from app.contracts.personalization import PersonalizationInput
from app.domain.personalization_engine.engine import adjust

SEOUL = ZoneInfo("Asia/Seoul")
PREP_START = datetime(2026, 8, 20, 7, 0, tzinfo=SEOUL)

#: Timed-routine minutes the plan engine baked into the window on top of the
#: estimate.  Held constant so the tests exercise the estimate, not the window.
FIXED_PREP_MINUTES = 10
TRAVEL_MINUTES = 40
TRAFFIC_BUFFER_MINUTES = 5

FLOOR = 10
MAX_STEP = 15
COLD_STEP = 20
SEED = 30.0
CEILING = SEED * 2.0

FULL_CONFIG = {
    "prepEmaAlpha": 0.3,
    "lateWeight": 1.5,
    "earlyWeight": 0.7,
    "maxStepMinutes": MAX_STEP,
    "coldStepMinutes": COLD_STEP,
    "prepFloorMinutes": FLOOR,
    "prepCeilingRatio": 2.0,
    "seedFallbackMinutes": 30,
    "coldStartSampleThreshold": 3,
    "clockSkewToleranceSeconds": 120,
    "prepOutlierMaxMinutes": 240,
    "geoMinConfidence": 0.6,
    "attributionMinSignalMinutes": 3,
    "modelVersion": "v1",
}


def make_payload(
    *,
    estimate: float,
    sample_count: int,
    start_delay: int = 0,
    prep_duration: int | None = None,
    lingering: int = 0,
    transit_error: int = 0,
    arrival_result: str = "on_time",
    seed_minutes: float = SEED,
    cold_start_adjusted: bool = False,
) -> PersonalizationInput:
    """Build one observation around a plan that used ``estimate``.

    ``prep_duration`` defaults to exactly what the plan allotted, which makes
    every delay signal zero — the neutral observation.
    """
    planned_estimate = int(round(estimate))
    window = planned_estimate + FIXED_PREP_MINUTES
    if prep_duration is None:
        prep_duration = window

    recommended_depart_at = PREP_START + timedelta(minutes=window)
    started_at = PREP_START + timedelta(minutes=start_delay)
    finished_at = started_at + timedelta(minutes=prep_duration)
    ready_at = max(finished_at, recommended_depart_at)
    departed_at = ready_at + timedelta(minutes=lingering)
    arrived_at = departed_at + timedelta(minutes=TRAVEL_MINUTES + transit_error)

    return PersonalizationInput.model_validate(
        {
            "eventId": "evt-prop",
            "planned": {
                "prepStartAt": PREP_START.isoformat(),
                "recommendedDepartAt": recommended_depart_at.isoformat(),
                "targetArriveAt": (
                    recommended_depart_at + timedelta(minutes=TRAVEL_MINUTES)
                ).isoformat(),
                "estimatedPrepMinutes": planned_estimate,
                "travelMinutes": TRAVEL_MINUTES,
                "trafficBufferMinutes": TRAFFIC_BUFFER_MINUTES,
            },
            "actual": {
                "actualPrepStartedAt": started_at.isoformat(),
                "actualPrepFinishedAt": finished_at.isoformat(),
                "actualDepartedAt": departed_at.isoformat(),
                "actualArrivedAt": arrived_at.isoformat(),
                "resultSource": "user",
            },
            "outcome": {"arrivalResult": arrival_result},
            "currentEstimate": {
                "estimatedMinutes": estimate,
                "sampleCount": sample_count,
                "modelVersion": "v1",
                "seedMinutes": seed_minutes,
                "coldStartAdjusted": cold_start_adjusted,
            },
            "config": FULL_CONFIG,
        }
    )


ARRIVAL_RESULTS = st.sampled_from(["early", "on_time", "rushed", "late"])


# ──────────────────────────────────────────────────────────────────────────────
# ① P ∈ [10, 시드×2] · 1회 변화 ≤ 15분 (콜드 스타트 예외 20분)
# ──────────────────────────────────────────────────────────────────────────────


@settings(max_examples=300, deadline=None)
@given(
    estimate=st.floats(min_value=10.0, max_value=60.0, allow_nan=False),
    sample_count=st.integers(min_value=0, max_value=50),
    start_delay=st.integers(min_value=0, max_value=90),
    prep_duration=st.integers(min_value=1, max_value=240),
    lingering=st.integers(min_value=0, max_value=60),
    transit_error=st.integers(min_value=-20, max_value=90),
    arrival_result=ARRIVAL_RESULTS,
)
def test_guardrails_hold_for_any_observation(
    estimate: float,
    sample_count: int,
    start_delay: int,
    prep_duration: int,
    lingering: int,
    transit_error: int,
    arrival_result: str,
) -> None:
    payload = make_payload(
        estimate=estimate,
        sample_count=sample_count,
        start_delay=start_delay,
        prep_duration=prep_duration,
        lingering=lingering,
        transit_error=transit_error,
        arrival_result=arrival_result,
    )
    result = adjust(payload)

    if result.adjusted_knob is not AdjustmentKnob.PREP_ESTIMATE:
        return

    assert result.previous_value is not None
    assert result.new_value is not None

    # Bounds.
    assert FLOOR <= result.new_value <= CEILING

    # Step limit — the cold-start exception is the only wider one.
    step_limit = COLD_STEP if sample_count < 3 else MAX_STEP
    assert abs(result.new_value - result.previous_value) <= step_limit + 1e-9


@settings(max_examples=200, deadline=None)
@given(
    estimate=st.floats(min_value=10.0, max_value=60.0, allow_nan=False),
    prep_duration=st.integers(min_value=1, max_value=240),
    arrival_result=ARRIVAL_RESULTS,
)
def test_cold_start_holds_the_seed_unless_a_failure(
    estimate: float,
    prep_duration: int,
    arrival_result: str,
) -> None:
    """§6.2 — under three samples only a clear failure may correct, once."""
    result = adjust(
        make_payload(
            estimate=estimate,
            sample_count=2,
            prep_duration=prep_duration,
            arrival_result=arrival_result,
            cold_start_adjusted=True,
        )
    )
    # The one-off cold-start correction was already spent.
    assert result.adjusted_knob is not AdjustmentKnob.PREP_ESTIMATE


# ──────────────────────────────────────────────────────────────────────────────
# ② 교통 지연만 있는 시퀀스에서 P 는 변하지 않는다
# ──────────────────────────────────────────────────────────────────────────────


@settings(max_examples=100, deadline=None)
@given(
    estimate=st.floats(min_value=12.0, max_value=55.0, allow_nan=False),
    transit_errors=st.lists(
        st.integers(min_value=3, max_value=90), min_size=1, max_size=12
    ),
    arrival_result=st.sampled_from(["late", "rushed"]),
)
def test_traffic_only_sequence_never_moves_the_estimate(
    estimate: float,
    transit_errors: list[int],
    arrival_result: str,
) -> None:
    current = estimate
    sample_count = 10
    for transit_error in transit_errors:
        result = adjust(
            make_payload(
                estimate=current,
                sample_count=sample_count,
                transit_error=transit_error,
                arrival_result=arrival_result,
            )
        )
        assert result.cause.value == "traffic"
        assert result.adjusted_knob is not AdjustmentKnob.PREP_ESTIMATE
        sample_count += 1

    assert current == estimate


# ──────────────────────────────────────────────────────────────────────────────
# ③ 전부 on_time 인 시퀀스에서 P 는 발산하지 않는다
# ──────────────────────────────────────────────────────────────────────────────


@settings(max_examples=100, deadline=None)
@given(
    estimate=st.floats(min_value=12.0, max_value=55.0, allow_nan=False),
    durations=st.lists(st.integers(min_value=5, max_value=90), min_size=5, max_size=30),
)
def test_on_time_sequence_does_not_diverge(
    estimate: float,
    durations: list[int],
) -> None:
    current = round(estimate, 1)
    sample_count = 10
    for prep_duration in durations:
        result = adjust(
            make_payload(
                estimate=current,
                sample_count=sample_count,
                prep_duration=prep_duration,
                arrival_result="on_time",
            )
        )
        if result.adjusted_knob is AdjustmentKnob.PREP_ESTIMATE:
            assert result.new_value is not None
            assert abs(result.new_value - current) <= MAX_STEP + 1e-9
            current = result.new_value
        assert FLOOR <= current <= CEILING
        sample_count += 1


@settings(max_examples=100, deadline=None)
@given(
    estimate=st.floats(min_value=12.0, max_value=55.0, allow_nan=False),
    length=st.integers(min_value=3, max_value=20),
)
def test_neutral_observations_reach_a_fixed_point(
    estimate: float,
    length: int,
) -> None:
    """An observation that matches the plan exactly must not drift the estimate."""
    current = round(estimate, 1)
    for _ in range(length):
        result = adjust(make_payload(estimate=current, sample_count=10))
        if result.adjusted_knob is AdjustmentKnob.PREP_ESTIMATE:
            assert result.new_value is not None
            current = result.new_value
        assert FLOOR <= current <= CEILING

    # The estimate is quantised to whole planned minutes, so the fixed point is
    # within rounding distance of where it started.
    assert abs(current - round(estimate, 1)) <= 1.0
