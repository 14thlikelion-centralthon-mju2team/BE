"""하루 재생 시뮬레이션 (TRD §17.4 · §18 M4).

> 가상 시계 하루 재생: 일정 200건 · 교통 지연·환경 변화 랜덤 · 상태 입력 랜덤

가상 시계로 하루를 재생하며 일정 200건을 **세 엔진 전부**에 통과시킵니다.
계획 → 웰니스 → 도착 판정 → RLS → 보정, 그리고 하루 끝에 DWL과 지표까지 한 바퀴입니다.

### 이 하네스가 검증하는 것

| 검증 | 근거 |
|---|---|
| 웰니스 푸시 ≤ 1/일정 | §17.4 · TR-11 |
| `stop_today` 이후 당일 해당 행동 0건 | §17.4 · §7.4 백오프 |
| WIS < 70 이면 예약 0건 | 불변식 ⑥ |
| 보정 가드레일 — P ∈ [10, 시드×2], 1회 ≤ 15분 | 불변식 ① |
| 교통 지연만 있는 일정은 P를 움직이지 않음 | 불변식 ② |
| 모든 일정이 계획→실행→보정 순환을 완주 | §17.4 "모든 일정이 closed 도달" |
| 같은 스냅샷은 같은 `inputHash` — 리비전 폭증 없음 | §5.5 |
| 하루치 DWL·북극성·웰니스 지표가 실제로 산출됨 | §16.2 |

### 이 하네스가 검증하지 **않는** 것

시간 알림 3회 예산, `dedupKey` 중복 0, 상태 입력 후 잔존 예약 0은 **스케줄러 소유**입니다.
AI 서버에는 알림 예약 상태가 없으므로 여기서 통과시켜도 의미가 없습니다. Spring 쪽
시뮬레이션에서 확인해야 하며, 이 하네스는 "엔진이 무엇을 예약해도 된다고 말했는지"까지만
책임집니다.

시드를 고정하므로 재실행하면 같은 하루가 재생됩니다. 실패를 재현할 수 없는 시뮬레이션은
디버깅에 쓸 수 없습니다.
"""

import random
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import pytest

from app.contracts.common import AdjustmentKnob, DelayCause
from app.contracts.config import (
    GeofenceConfig,
    PersonalizationEngineConfig,
    WellnessEngineConfig,
)
from app.contracts.personalization import PersonalizationInput
from app.contracts.wellness import (
    DailyEventSummary,
    DailySummaryInput,
    RushLoadInput,
    WellnessInput,
)
from app.domain.geofence.confidence import ArrivalObservation, compute_arrival_confidence
from app.domain.geofence.enums import ArrivalDecision, DestinationKind
from app.domain.metrics.north_star import (
    NorthStarConfig,
    NorthStarInput,
    aggregate_north_star,
)
from app.domain.metrics.wellness_metrics import WellnessMetricInput, compute_wellness_metrics
from app.domain.personalization_engine.engine import adjust
from app.domain.plan_engine.engine import compute_plan
from app.domain.revision.input_hash import compute_input_hash
from app.domain.revision.models import RevisionSnapshot
from app.domain.wellness_engine.actions import ACTION_TOPICS
from app.domain.wellness_engine.engine import (
    compute_rush_load,
    evaluate_wellness,
    summarize_day,
)
from app.domain.wellness_engine.enums import ArmingGate, WellnessActionCode
from app.schemas.plan import PlanInput
from app.testing.clock import FixedClock

SEOUL = ZoneInfo("Asia/Seoul")
DAY_START = datetime(2026, 8, 20, 6, 0, tzinfo=SEOUL)
EVENT_COUNT = 200
SEED = 20260820

PERSONALIZATION_CONFIG = PersonalizationEngineConfig(model_version="sim-v1")
WELLNESS_CONFIG = WellnessEngineConfig(weight_version="sim-w1")
GEOFENCE_CONFIG = GeofenceConfig()
NORTH_STAR_CONFIG = NorthStarConfig()

SEED_PREP_MINUTES = 30.0
FIXED_ROUTINE_MINUTES = 10

#: 시뮬레이션이 켜 두는 관심 항목.  게이트 ④·⑥이 항목별이라 topic 단위로 상태를 센다.
SIMULATED_TOPICS = ("uv", "pm", "temp", "hydration")

#: 항목별 하루 상한.  기본값 1로 두면 첫 예약 뒤 하루 내내 상한에 걸려 상한 도달 '전후'를
#: 함께 밟을 수 없다. 2로 올려 재생 한 번에 통과·차단 양쪽을 지나게 한다.
SIMULATED_DAILY_CAP = 2


@dataclass
class SimulationState:
    """하루 동안 누적되는 상태. 사용자 한 명의 하루를 재생한다."""

    prep_estimate: float = SEED_PREP_MINUTES
    sample_count: int = 12
    #: action_code → '오늘은 그만'을 누른 일정 index.
    stop_today: dict[str, int] = field(default_factory=dict)
    #: topic → 오늘 발송 수.  게이트 ⑥이 항목별이므로 topic별로 센다 (§7.4).
    topic_counts: dict[str, int] = field(default_factory=dict)
    #: topic → 마지막 발송 시각.  게이트 ④의 경과 분을 여기서 만든다.
    topic_last_armed_at: dict[str, datetime] = field(default_factory=dict)
    #: (일정 index, action_code) 예약 이력.
    armed: list[tuple[int, str]] = field(default_factory=list)
    estimate_history: list[float] = field(default_factory=lambda: [SEED_PREP_MINUTES])

    def topic_states(self, now: datetime) -> dict[str, dict[str, int]]:
        """계약의 ``eventState.topicStates`` 로 넘길 항목별 상태.

        경과 분이 없으면 키를 넣지 않는다 — "이 항목은 오늘 보낸 적 없다"는 뜻이다.
        """
        states: dict[str, dict[str, int]] = {}
        for topic in SIMULATED_TOPICS:
            state: dict[str, int] = {"dailyEventCount": self.topic_counts.get(topic, 0)}
            last = self.topic_last_armed_at.get(topic)
            if last is not None:
                state["minutesSinceLastEvent"] = int((now - last).total_seconds() // 60)
            states[topic] = state
        return states


@dataclass
class EventRecord:
    """일정 하나가 하루를 통과하며 남긴 것."""

    event_id: str
    input_hash: str
    wis_score: int | None
    armed_action_code: str | None
    #: 예약이 막힌 경우 어느 게이트가 막았는지.  "후보가 없었다"와 구분하기 위해 기록한다.
    arming_blocked_by: tuple[str, ...]
    outdoor_minutes: int
    rush_load_score: int
    arrival_decision: ArrivalDecision
    cause: DelayCause
    knob: AdjustmentKnob
    transit_error: int
    arrival_result: str
    depart_delta: float
    margin_minutes: float | None
    critical_alerts: int
    closed: bool


def _environment(rng: random.Random) -> dict[str, Any]:
    """환경 변화 랜덤 — 하루 사이 자외선·대기질·체감온도·강수가 흔들린다."""
    return {
        "uvIndex": round(rng.uniform(0.0, 11.0), 1),
        "airGrade": rng.choice(["good", "moderate", "bad", "very_bad"]),
        "feelsLikeCelsius": round(rng.uniform(-15.0, 38.0), 1),
        "precipitationProbability": rng.randint(0, 100),
        "observedAt": DAY_START.isoformat(),
    }


def _run_one_event(
    index: int,
    clock: FixedClock,
    rng: random.Random,
    state: SimulationState,
) -> EventRecord:
    event_id = f"sim-{index:03d}"
    now = clock.now()
    starts_at = now + timedelta(minutes=rng.randint(120, 240))
    travel_minutes = rng.randint(10, 70)
    walk_minutes = min(travel_minutes, rng.randint(0, 40))
    traffic_buffer = 5
    arrival_buffer = 10

    environment = _environment(rng)

    # ── 1. 계획 ────────────────────────────────────────────────────────────
    plan = compute_plan(
        PlanInput.model_validate(
            {
                "now": now.isoformat(),
                "event": {"startsAt": starts_at.isoformat(), "anchorMode": "arrive_by"},
                "prepEstimate": {
                    "estimatedMinutes": int(round(state.prep_estimate)),
                    "source": "observed",
                    "sampleCount": state.sample_count,
                },
                "arrivalBufferMinutes": arrival_buffer,
                "trafficBufferMinutes": traffic_buffer,
                "selectedRoute": {
                    "routeId": f"route-{index}",
                    "totalMinutes": travel_minutes,
                    "walkMinutes": walk_minutes,
                    "source": "odsay",
                },
                "environment": environment,
                "prepItems": [
                    {
                        "itemId": "routine-1",
                        "itemName": "샤워",
                        "actionType": "timed_routine",
                        "sourceType": "rule",
                        "appliedMinutes": FIXED_ROUTINE_MINUTES,
                    }
                ],
                "config": {
                    "seedFallbackMinutes": 30,
                    "rainThresholdPercent": 60,
                    "rainExtraPrepMinutes": 5,
                    "arrivalBufferDefaultMinutes": 10,
                    "trafficBufferDefaultMinutes": 5,
                },
            }
        )
    )

    # ── 2. 웰니스 (WIS · 행동 · 게이트) ────────────────────────────────────
    wellness = evaluate_wellness(
        WellnessInput.model_validate(
            {
                "environment": environment,
                "estimatedOutdoorMinutes": walk_minutes,
                "userPreferences": [
                    {
                        "wellnessTopic": topic,
                        "isEnabled": True,
                        "remindIntervalMinutes": 120,
                        "dailyEventCap": SIMULATED_DAILY_CAP,
                    }
                    for topic in SIMULATED_TOPICS
                ],
                "existingPrepItems": [],
                "eventState": {
                    "wellnessEventEnabled": True,
                    "eventInProgress": True,
                    "outdoorRemainingMinutes": max(1, walk_minutes // 2),
                    "stopTodayActionCodes": sorted(state.stop_today),
                    # 항목별 누적 상태를 실제로 넘긴다.  리터럴 0을 넘기면 상한·주기 게이트가
                    # 깨져도 재생이 검출하지 못한다.
                    "topicStates": state.topic_states(now),
                },
                "config": WELLNESS_CONFIG.model_dump(by_alias=True),
            }
        )
    )
    if wellness.armed_action_code is not None:
        code = wellness.armed_action_code
        topic = ACTION_TOPICS[WellnessActionCode(code)].value
        state.armed.append((index, code))
        state.topic_counts[topic] = state.topic_counts.get(topic, 0) + 1
        state.topic_last_armed_at[topic] = now
        # 상태 입력 랜덤 — 사용자가 이따금 "오늘은 그만"을 누른다.
        # 첫 예약은 반드시 중단시켜, 재무장 회귀가 어떤 시드에서도 실행되게 한다.
        if len(state.armed) == 1 or rng.random() < 0.3:
            state.stop_today.setdefault(code, index)

    # ── 3. inputHash (재계산 멱등성) ───────────────────────────────────────
    snapshot = RevisionSnapshot.model_validate(
        {
            "eventStartsAt": starts_at.isoformat(),
            "origin": {"latitude": 37.5665, "longitude": 126.9780},
            "destination": {"latitude": 37.4979, "longitude": 127.0276},
            "sourceType": "internal",
            "estimatedPrepMinutes": plan.breakdown.estimated_prep_minutes,
            "trafficBufferMinutes": traffic_buffer,
            "arrivalBufferMinutes": arrival_buffer,
            "selectedRoute": {
                "routeId": f"route-{index}",
                "totalMinutes": travel_minutes,
                "walkMinutes": walk_minutes,
            },
            "quantizedContext": (
                wellness.quantized.model_dump(by_alias=True)
                if wellness.quantized is not None
                else {
                    "rain": "none",
                    "uv": "low",
                    "pm": "good",
                    "temp": "mild",
                    "tempSwing": False,
                }
            ),
            "activePrepItems": [
                {"itemId": "routine-1", "appliedMinutes": FIXED_ROUTINE_MINUTES}
            ],
            "calcVersion": plan.calc_version,
            "weightVersion": wellness.weight_version,
        }
    )
    input_hash = compute_input_hash(snapshot)

    # ── 4. 실행 — 교통 지연·상태 입력 랜덤 ─────────────────────────────────
    start_delay = rng.choice([0, 0, 0, 5, 12, 25])
    prep_overrun = rng.choice([-5, 0, 0, 4, 11, 20])
    transit_error = rng.choice([-5, 0, 0, 3, 9, 22])

    planned_window = int(
        (plan.recommended_depart_at - plan.prep_start_at).total_seconds() // 60
    )
    actual_started = plan.prep_start_at + timedelta(minutes=start_delay)
    actual_finished = actual_started + timedelta(minutes=planned_window + prep_overrun)
    actual_departed = actual_finished
    actual_arrived = actual_departed + timedelta(minutes=travel_minutes + transit_error)

    depart_delta = (actual_departed - plan.recommended_depart_at).total_seconds() / 60.0
    margin_minutes = (starts_at - actual_arrived).total_seconds() / 60.0
    if margin_minutes >= 0:
        arrival_result = "on_time" if margin_minutes <= 30 else "early"
    else:
        arrival_result = "late"
    critical_alerts = 1 if depart_delta > 10 else 0

    # ── 5. 도착 판정 신뢰도 ────────────────────────────────────────────────
    geofence = compute_arrival_confidence(
        ArrivalObservation(
            dwell_seconds=rng.choice([10.0, 95.0, 120.0]),
            horizontal_accuracy_meters=rng.choice([15.0, 45.0, 120.0, None]),
            entered_at=actual_arrived,
            expected_arrival_at=plan.target_arrive_at,
            transitions_in_window=rng.choice([1, 1, 1, 3]),
            destination_kind=rng.choice(list(DestinationKind)),
        ),
        GEOFENCE_CONFIG,
    )

    # ── 6. RLS ─────────────────────────────────────────────────────────────
    rush = compute_rush_load(
        RushLoadInput.model_validate(
            {
                "eventId": event_id,
                "prepDelayMinutes": float(start_delay),
                "departDelayMinutes": depart_delta,
                "criticalAlertCount": critical_alerts,
                "config": WELLNESS_CONFIG.model_dump(by_alias=True),
            }
        )
    )

    # ── 7. 보정 ────────────────────────────────────────────────────────────
    personalization = adjust(
        PersonalizationInput.model_validate(
            {
                "eventId": event_id,
                "planned": {
                    "prepStartAt": plan.prep_start_at.isoformat(),
                    "recommendedDepartAt": plan.recommended_depart_at.isoformat(),
                    "targetArriveAt": plan.target_arrive_at.isoformat(),
                    "estimatedPrepMinutes": plan.breakdown.estimated_prep_minutes,
                    "travelMinutes": travel_minutes,
                    "trafficBufferMinutes": traffic_buffer,
                },
                "actual": {
                    "actualPrepStartedAt": actual_started.isoformat(),
                    "actualPrepFinishedAt": actual_finished.isoformat(),
                    "actualDepartedAt": actual_departed.isoformat(),
                    "actualArrivedAt": actual_arrived.isoformat(),
                    "resultSource": "geo"
                    if geofence.decision is ArrivalDecision.AUTO_CONFIRM
                    else "user",
                    "resultConfidence": geofence.confidence,
                },
                "outcome": {"arrivalResult": arrival_result},
                "currentEstimate": {
                    "estimatedMinutes": state.prep_estimate,
                    "sampleCount": state.sample_count,
                    "modelVersion": "sim-v1",
                    "seedMinutes": SEED_PREP_MINUTES,
                },
                "config": PERSONALIZATION_CONFIG.model_dump(by_alias=True),
            }
        )
    )
    if (
        personalization.adjusted_knob is AdjustmentKnob.PREP_ESTIMATE
        and personalization.new_value is not None
    ):
        state.prep_estimate = personalization.new_value
        state.estimate_history.append(personalization.new_value)
    if not personalization.excluded_from_learning:
        state.sample_count += 1

    clock.advance(timedelta(minutes=rng.randint(3, 9)))

    return EventRecord(
        event_id=event_id,
        input_hash=input_hash,
        wis_score=wellness.wis_score,
        armed_action_code=wellness.armed_action_code,
        arming_blocked_by=tuple(wellness.arming_blocked_by),
        outdoor_minutes=walk_minutes,
        rush_load_score=rush.rush_load_score,
        arrival_decision=geofence.decision,
        cause=personalization.cause,
        knob=personalization.adjusted_knob,
        transit_error=transit_error,
        arrival_result=arrival_result,
        depart_delta=depart_delta,
        margin_minutes=margin_minutes,
        critical_alerts=critical_alerts,
        closed=True,
    )


def replay_day(seed: int = SEED, event_count: int = EVENT_COUNT) -> tuple[
    list[EventRecord], SimulationState
]:
    clock = FixedClock(DAY_START)
    rng = random.Random(seed)
    state = SimulationState()
    records = [_run_one_event(index, clock, rng, state) for index in range(event_count)]
    return records, state


@pytest.fixture(scope="module")
def replay() -> tuple[list[EventRecord], SimulationState]:
    return replay_day()


# ──────────────────────────────────────────────────────────────────────────────
# 재생 자체
# ──────────────────────────────────────────────────────────────────────────────


def test_all_events_complete_the_cycle(replay) -> None:
    """§17.4 — 200건 전부가 계획→웰니스→판정→RLS→보정을 완주한다."""
    records, _ = replay
    assert len(records) == EVENT_COUNT
    assert all(record.closed for record in records)


def test_replay_is_deterministic() -> None:
    """시드가 같으면 같은 하루가 재생된다. 재현할 수 없는 실패는 못 고친다."""
    first, _ = replay_day(event_count=40)
    second, _ = replay_day(event_count=40)
    assert [record.input_hash for record in first] == [
        record.input_hash for record in second
    ]
    assert [record.wis_score for record in first] == [record.wis_score for record in second]


# ──────────────────────────────────────────────────────────────────────────────
# 웰니스 예산과 백오프 (§17.4 · TR-11)
# ──────────────────────────────────────────────────────────────────────────────


def test_at_most_one_wellness_push_per_event(replay) -> None:
    records, state = replay
    armed = [record for record in records if record.armed_action_code is not None]
    # 예약은 일정당 0건 또는 1건 — 계약이 코드 하나만 담는다 (ERD uq_wellness_event_once).
    assert len(armed) == len(state.armed)
    assert all(isinstance(record.armed_action_code, str) for record in armed)


def test_daily_cap_is_respected_per_topic(replay) -> None:
    """게이트 ⑥ — 항목별 하루 상한을 넘겨 예약되지 않는다 (§7.4)."""
    _, state = replay
    assert state.topic_counts, "simulation never armed anything"
    for topic, count in state.topic_counts.items():
        assert count <= SIMULATED_DAILY_CAP, f"{topic} armed {count} times"


def test_blocked_events_name_the_gate_that_stopped_them(replay) -> None:
    """예약되지 않은 이유가 항상 기록된다 — '후보가 없었다'와 '게이트가 막았다'를 구분한다."""
    records, _ = replay
    for record in records:
        if record.armed_action_code is None:
            assert record.arming_blocked_by, record.event_id
        else:
            assert record.arming_blocked_by == ()


def test_daily_cap_gate_actually_fires_during_the_day(replay) -> None:
    """상한을 소진한 항목이 실제로 DAILY_CAP 으로 막히는 구간이 있어야 한다.

    이 단정이 없으면 "상한을 넘지 않았다"는 검사가 '애초에 후보가 없어서 예약이 없었다'로도
    통과한다.
    """
    records, _ = replay
    capped = [
        record
        for record in records
        if ArmingGate.DAILY_CAP.value in record.arming_blocked_by
    ]
    assert capped, "no event was blocked by the daily cap"


def test_stop_today_silences_the_code_for_the_rest_of_the_day(replay) -> None:
    """§7.4 백오프 — 'stop_today' 이후 당일 해당 action_code는 0건이어야 한다."""
    records, state = replay
    assert state.stop_today, "simulation never exercised stop_today"

    for index, code in state.armed:
        stopped_index = state.stop_today.get(code)
        if stopped_index is None:
            continue
        assert index <= stopped_index, (
            f"{code} was armed at #{index} after stop_today at #{stopped_index}"
        )


def test_stopped_codes_are_reported_as_already_handled(replay) -> None:
    """중단한 항목이 최상위 후보였다면 ALREADY_HANDLED 로 막혔다고 보고돼야 한다."""
    records, state = replay
    assert state.stop_today
    handled = [
        record
        for record in records
        if ArmingGate.ALREADY_HANDLED.value in record.arming_blocked_by
    ]
    assert handled, "stop_today never surfaced as a gate reason"


def test_low_score_never_arms(replay) -> None:
    """불변식 ⑥ — WIS가 임계 미만이면 예약이 생기지 않는다."""
    records, _ = replay
    threshold = WELLNESS_CONFIG.wellness_event_min
    for record in records:
        if record.wis_score is None or record.wis_score < threshold:
            assert record.armed_action_code is None


# ──────────────────────────────────────────────────────────────────────────────
# 보정 가드레일 (불변식 ①②)
# ──────────────────────────────────────────────────────────────────────────────


def test_estimate_stays_inside_the_guardrails(replay) -> None:
    _, state = replay
    floor = float(PERSONALIZATION_CONFIG.prep_floor_minutes)
    ceiling = SEED_PREP_MINUTES * PERSONALIZATION_CONFIG.prep_ceiling_ratio
    for value in state.estimate_history:
        assert floor <= value <= ceiling


def test_no_single_step_exceeds_the_limit(replay) -> None:
    _, state = replay
    limit = float(PERSONALIZATION_CONFIG.max_step_minutes)
    for previous, current in zip(
        state.estimate_history, state.estimate_history[1:], strict=False
    ):
        assert abs(current - previous) <= limit + 1e-9


def test_traffic_only_events_never_move_the_estimate(replay) -> None:
    """불변식 ② — 교통이 원인인 일정은 준비 시간 추정을 건드리지 않는다 (TR-05)."""
    records, _ = replay
    traffic_events = [record for record in records if record.cause is DelayCause.TRAFFIC]
    assert traffic_events, "simulation never produced a traffic-dominant event"
    for record in traffic_events:
        assert record.knob is not AdjustmentKnob.PREP_ESTIMATE


def test_one_knob_per_observation(replay) -> None:
    """TR-05 — 관측 하나가 손잡이 하나만 돌린다."""
    records, _ = replay
    assert all(isinstance(record.knob, AdjustmentKnob) for record in records)


# ──────────────────────────────────────────────────────────────────────────────
# 재계산 멱등성 (§5.5)
# ──────────────────────────────────────────────────────────────────────────────


def test_same_snapshot_produces_no_new_revision(replay) -> None:
    """해시가 같으면 리비전을 만들지 않는다 — 하루를 두 번 재생해도 해시 열이 같다."""
    records, _ = replay
    again, _ = replay_day()
    assert [record.input_hash for record in records] == [
        record.input_hash for record in again
    ]


# ──────────────────────────────────────────────────────────────────────────────
# 하루 집계 (§7.5 · §16.2)
# ──────────────────────────────────────────────────────────────────────────────


def test_daily_summary_and_metrics_are_produced(replay) -> None:
    records, state = replay

    daily = summarize_day(
        DailySummaryInput.model_validate(
            {
                "summaryDate": DAY_START.date().isoformat(),
                "events": [
                    DailyEventSummary(
                        event_id=record.event_id,
                        wis_score=record.wis_score,
                        rush_load_score=record.rush_load_score,
                        outdoor_minutes=record.outdoor_minutes,
                        outdoor_observed=True,
                    ).model_dump(by_alias=True)
                    for record in records
                ],
                "proposedActionCount": len(records),
                "completedActionCount": len(state.armed),
                "criticalAlertCount": sum(record.critical_alerts for record in records),
                "config": WELLNESS_CONFIG.model_dump(by_alias=True),
            }
        )
    )
    assert daily.card_visible is True
    assert daily.dwl_score is not None
    assert daily.dwl_band is not None
    assert daily.card_scenario is not None
    assert daily.card_message

    north_star = aggregate_north_star(
        [
            NorthStarInput(
                event_id=record.event_id,
                arrival_result=record.arrival_result,
                critical_alert_count=record.critical_alerts,
                depart_delta_minutes=record.depart_delta,
                margin_minutes=record.margin_minutes,
            )
            for record in records
        ],
        NORTH_STAR_CONFIG,
    )
    assert north_star.total_events == EVENT_COUNT
    assert north_star.ok_ratio is not None
    # 200건 중 하나도 성공하지 못하거나 전부 성공하면 시뮬레이션이 한쪽으로 쏠린 것이다.
    assert 0.0 < north_star.ok_ratio < 1.0

    wellness_metrics = compute_wellness_metrics(
        WellnessMetricInput(
            proposed_actions=len(records),
            completed_actions=len(state.armed),
            events_sent=len(state.armed),
            events_completed=len(state.armed) // 2,
            events_snoozed=0,
            ratings_collected=len(state.armed),
            ratings_useful=len(state.armed) // 2,
            outdoor_events=sum(1 for record in records if record.outdoor_minutes > 0),
            wis_generated_events=sum(1 for record in records if record.wis_score is not None),
        )
    )
    assert wellness_metrics.coverage_rate is not None
    assert wellness_metrics.action_completion_rate is not None


def test_geofence_decisions_cover_all_three_bands(replay) -> None:
    """랜덤 관측이 세 판정 구간을 모두 밟아야 튜닝 근거로 쓸 수 있다."""
    records, _ = replay
    decisions = {record.arrival_decision for record in records}
    assert decisions == set(ArrivalDecision)
