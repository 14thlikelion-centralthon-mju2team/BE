"""Property tests for the wellness engine (TRD §17.3 불변식 ④⑤⑥).

    ④ WIS 는 입력 정규화값에 단조 — 자외선이 오르는데 점수가 내리는 일이 없다
    ⑤ 동일 입력 재실행 시 점수·행동 완전 동일 (순수성)
    ⑥ wisScore < 70 이면 웰니스 푸시 후보가 절대 생성되지 않는다 (TR-11)
"""

from typing import Any

from hypothesis import given, settings
from hypothesis import strategies as st

from app.contracts.config import WellnessEngineConfig
from app.contracts.wellness import MAX_WELLNESS_ACTIONS, WellnessInput
from app.domain.wellness_engine.engine import evaluate_wellness

FULL_CONFIG: dict[str, Any] = WellnessEngineConfig(
    weight_version="prop-w1"
).model_dump(by_alias=True)

AIR_GRADES = ["good", "moderate", "bad", "very_bad"]
#: Grade order by load — the monotonicity check walks it upwards.
GRADE_ORDER = {grade: index for index, grade in enumerate(AIR_GRADES)}


def build(
    *,
    uv_index: float | None = 5.0,
    air_grade: str | None = "good",
    feels_like: float | None = 20.0,
    precipitation: int | None = 0,
    outdoor_minutes: int | None = 60,
    preferences: list[dict[str, Any]] | None = None,
    event_state: dict[str, Any] | None = None,
    prep_items: list[dict[str, Any]] | None = None,
) -> WellnessInput:
    environment: dict[str, Any] = {
        "uvIndex": uv_index,
        "airGrade": air_grade,
        "feelsLikeCelsius": feels_like,
        "precipitationProbability": precipitation,
    }
    return WellnessInput.model_validate(
        {
            "environment": environment,
            "estimatedOutdoorMinutes": outdoor_minutes,
            "userPreferences": preferences or [],
            "existingPrepItems": prep_items or [],
            "eventState": event_state or {},
            "config": FULL_CONFIG,
        }
    )


def score_of(payload: WellnessInput) -> int:
    score = evaluate_wellness(payload).wis_score
    assert score is not None
    return score


# ──────────────────────────────────────────────────────────────────────────────
# ④ 단조성
# ──────────────────────────────────────────────────────────────────────────────


@settings(max_examples=200, deadline=None)
@given(
    low_uv=st.floats(min_value=0.0, max_value=12.0, allow_nan=False),
    delta=st.floats(min_value=0.0, max_value=12.0, allow_nan=False),
    air_grade=st.sampled_from(AIR_GRADES),
    feels_like=st.floats(min_value=-20.0, max_value=45.0, allow_nan=False),
    outdoor=st.integers(min_value=0, max_value=240),
)
def test_wis_is_monotone_in_uv(
    low_uv: float, delta: float, air_grade: str, feels_like: float, outdoor: int
) -> None:
    lower = score_of(
        build(uv_index=low_uv, air_grade=air_grade, feels_like=feels_like, outdoor_minutes=outdoor)
    )
    higher = score_of(
        build(
            uv_index=low_uv + delta,
            air_grade=air_grade,
            feels_like=feels_like,
            outdoor_minutes=outdoor,
        )
    )
    assert higher >= lower


@settings(max_examples=200, deadline=None)
@given(
    low_outdoor=st.integers(min_value=0, max_value=240),
    delta=st.integers(min_value=0, max_value=240),
    uv_index=st.floats(min_value=0.0, max_value=12.0, allow_nan=False),
    air_grade=st.sampled_from(AIR_GRADES),
)
def test_wis_is_monotone_in_outdoor_minutes(
    low_outdoor: int, delta: int, uv_index: float, air_grade: str
) -> None:
    lower = score_of(
        build(outdoor_minutes=low_outdoor, uv_index=uv_index, air_grade=air_grade)
    )
    higher = score_of(
        build(outdoor_minutes=low_outdoor + delta, uv_index=uv_index, air_grade=air_grade)
    )
    assert higher >= lower


@settings(max_examples=200, deadline=None)
@given(
    grades=st.tuples(st.sampled_from(AIR_GRADES), st.sampled_from(AIR_GRADES)),
    uv_index=st.floats(min_value=0.0, max_value=12.0, allow_nan=False),
    outdoor=st.integers(min_value=0, max_value=240),
)
def test_wis_is_monotone_in_air_grade(
    grades: tuple[str, str], uv_index: float, outdoor: int
) -> None:
    first, second = grades
    worse, better = (
        (first, second) if GRADE_ORDER[first] >= GRADE_ORDER[second] else (second, first)
    )
    assert score_of(build(air_grade=worse, uv_index=uv_index, outdoor_minutes=outdoor)) >= score_of(
        build(air_grade=better, uv_index=uv_index, outdoor_minutes=outdoor)
    )


@settings(max_examples=100, deadline=None)
@given(
    uv_index=st.floats(min_value=0.0, max_value=12.0, allow_nan=False),
    outdoor=st.integers(min_value=0, max_value=240),
)
def test_missing_data_never_scores_above_present_data(uv_index: float, outdoor: int) -> None:
    """부재는 0으로 가정한다 — 값이 있는 쪽보다 높게 나올 수 없다 (§7.2)."""
    absent = score_of(build(uv_index=None, outdoor_minutes=outdoor))
    present = score_of(build(uv_index=uv_index, outdoor_minutes=outdoor))
    assert present >= absent


# ──────────────────────────────────────────────────────────────────────────────
# ⑤ 순수성 · 계약 상한
# ──────────────────────────────────────────────────────────────────────────────


@settings(max_examples=200, deadline=None)
@given(
    uv_index=st.floats(min_value=0.0, max_value=12.0, allow_nan=False),
    air_grade=st.sampled_from(AIR_GRADES),
    feels_like=st.floats(min_value=-20.0, max_value=45.0, allow_nan=False),
    precipitation=st.integers(min_value=0, max_value=100),
    outdoor=st.integers(min_value=0, max_value=240),
)
def test_result_is_pure_and_capped(
    uv_index: float,
    air_grade: str,
    feels_like: float,
    precipitation: int,
    outdoor: int,
) -> None:
    payload = build(
        uv_index=uv_index,
        air_grade=air_grade,
        feels_like=feels_like,
        precipitation=precipitation,
        outdoor_minutes=outdoor,
    )
    first = evaluate_wellness(payload)
    second = evaluate_wellness(payload)
    assert first.model_dump() == second.model_dump()

    assert len(first.actions) <= MAX_WELLNESS_ACTIONS
    ranks = [action.display_rank for action in first.actions]
    assert ranks == list(range(1, len(ranks) + 1))
    assert first.wis_score is not None
    assert 0 <= first.wis_score <= 100


# ──────────────────────────────────────────────────────────────────────────────
# ⑥ 점수 게이트
# ──────────────────────────────────────────────────────────────────────────────


@settings(max_examples=300, deadline=None)
@given(
    uv_index=st.floats(min_value=0.0, max_value=12.0, allow_nan=False),
    air_grade=st.sampled_from(AIR_GRADES),
    feels_like=st.floats(min_value=-20.0, max_value=45.0, allow_nan=False),
    precipitation=st.integers(min_value=0, max_value=100),
    outdoor=st.integers(min_value=0, max_value=240),
    consent=st.booleans(),
    in_progress=st.booleans(),
    remaining=st.integers(min_value=0, max_value=120),
    since_last=st.integers(min_value=0, max_value=600),
    daily_count=st.integers(min_value=0, max_value=3),
    topic=st.sampled_from(["uv", "pm", "temp", "rain", "hydration"]),
)
def test_below_threshold_never_arms(
    uv_index: float,
    air_grade: str,
    feels_like: float,
    precipitation: int,
    outdoor: int,
    consent: bool,
    in_progress: bool,
    remaining: int,
    since_last: int,
    daily_count: int,
    topic: str,
) -> None:
    payload = build(
        uv_index=uv_index,
        air_grade=air_grade,
        feels_like=feels_like,
        precipitation=precipitation,
        outdoor_minutes=outdoor,
        preferences=[
            {
                "wellnessTopic": topic,
                "isEnabled": True,
                "remindIntervalMinutes": 60,
                "dailyEventCap": 2,
            }
        ],
        event_state={
            "wellnessEventEnabled": consent,
            "eventInProgress": in_progress,
            "outdoorRemainingMinutes": remaining,
            "minutesSinceLastEvent": since_last,
            "dailyEventCount": daily_count,
        },
    )
    result = evaluate_wellness(payload)
    threshold = payload.config.wellness_event_min

    if result.wis_score is None or result.wis_score < threshold:
        assert result.event_armed is False
        assert result.armed_action_code is None

    if result.event_armed:
        # Arming implies every gate opened, not just the score.
        assert result.wis_score is not None and result.wis_score >= threshold
        assert consent is True
        assert in_progress is True
        assert remaining > 0
        assert since_last >= 60
        assert daily_count < 2
        assert result.arming_blocked_by == []
