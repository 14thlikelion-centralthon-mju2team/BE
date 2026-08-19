"""Wellness event arming — the TR-11 gates (TRD §7.4 · PRD §12.7, §14.7).

> 웰니스 푸시는 4중 게이트를 전부 통과해야 발사된다

```
① 동의     USER_WELLNESS_PREF.is_enabled ∧ USER_SETTING.wellness_event_enabled
② 점수     wis_score ≥ WELLNESS_EVENT_MIN (항목별 상향 시 85)
③ 노출     일정 진행 중 ∧ 야외 노출 잔여 (실내 전환 추정 시 취소)
④ 주기     사용자가 정한 remind_interval_minutes 도달
⑤ 미완료   같은 일정·같은 action_code 에 completed / stop_today 없음
⑥ 일일 상한 daily_event_cap 미소진
```

기본값이 전부 보수적이라, 게이트 입력을 보내지 않는 호출자는 절대 푸시를 예약하지 못합니다.
주기는 사용자 설정값이고 서비스가 SPF·피부 타입·제품 성능을 판단하지 않습니다 (PRD §14.7).
"""

from dataclasses import dataclass

from app.contracts.common import WellnessTopic
from app.contracts.config import WellnessEngineConfig
from app.contracts.wellness import WellnessEventState, WellnessPreference
from app.domain.wellness_engine.actions import (
    ACTION_TOPICS,
    TOPIC_ORDER,
    triggered_in_event,
)
from app.domain.wellness_engine.enums import ArmingGate, WellnessActionCode
from app.domain.wellness_engine.quantize import EnvironmentBuckets


@dataclass(frozen=True)
class ArmingDecision:
    armed: bool
    action_code: WellnessActionCode | None
    blocked_by: tuple[ArmingGate, ...]


def _preference_for(
    topic: WellnessTopic,
    preferences: list[WellnessPreference],
) -> WellnessPreference | None:
    for preference in preferences:
        if preference.wellness_topic is topic:
            return preference
    return None


def _score_threshold(
    code: WellnessActionCode,
    state: WellnessEventState,
    config: WellnessEngineConfig,
) -> int:
    """Per-action threshold, raised to 85 after high opt-out rates (D9)."""
    if code.value in state.raised_threshold_action_codes:
        return config.wellness_event_min_raised
    return config.wellness_event_min


def _gates_for(
    code: WellnessActionCode,
    *,
    wis: int | None,
    state: WellnessEventState,
    preferences: list[WellnessPreference],
    config: WellnessEngineConfig,
) -> tuple[ArmingGate, ...]:
    blocked: list[ArmingGate] = []
    preference = _preference_for(ACTION_TOPICS[code], preferences)

    # ① 동의 — both opt-ins, both default false (D4).
    if not state.wellness_event_enabled or preference is None or not preference.is_enabled:
        blocked.append(ArmingGate.CONSENT)

    # ② 점수
    if wis is None or wis < _score_threshold(code, state, config):
        blocked.append(ArmingGate.SCORE)

    # ③ 노출 — 실내 전환이 추정되면 취소한다.
    remaining = state.outdoor_remaining_minutes or 0
    if not state.event_in_progress or remaining <= 0 or state.indoor_transition_estimated:
        blocked.append(ArmingGate.EXPOSURE)

    # ④ 주기 — 사용자가 주기를 정하지 않았으면 발사하지 않는다 (PRD §14.7).
    interval = preference.remind_interval_minutes if preference is not None else None
    if interval is None:
        blocked.append(ArmingGate.INTERVAL)
    elif state.minutes_since_last_event is not None and state.minutes_since_last_event < interval:
        blocked.append(ArmingGate.INTERVAL)

    # ⑤ 미완료 — completed 또는 stop_today 가 있으면 중단.
    if (
        code.value in state.completed_action_codes
        or code.value in state.stop_today_action_codes
    ):
        blocked.append(ArmingGate.ALREADY_HANDLED)

    # ⑥ 일일 상한 — 하루에 야외 일정이 3건이어도 같은 항목으로 3번 알리지 않는다.
    cap = preference.daily_event_cap if preference is not None else config.daily_event_cap_default
    if state.daily_event_count >= cap:
        blocked.append(ArmingGate.DAILY_CAP)

    return tuple(blocked)


def evaluate_arming(
    *,
    wis: int | None,
    buckets: EnvironmentBuckets,
    outdoor_minutes: int,
    contributions: dict[WellnessTopic, float],
    state: WellnessEventState,
    preferences: list[WellnessPreference],
    config: WellnessEngineConfig,
) -> ArmingDecision:
    """Arm at most one in-event action, or explain which gate stopped it."""
    exposure_minutes = (
        state.outdoor_remaining_minutes
        if state.outdoor_remaining_minutes is not None
        else outdoor_minutes
    )
    candidates = triggered_in_event(buckets, exposure_minutes)
    if not candidates:
        return ArmingDecision(
            armed=False, action_code=None, blocked_by=(ArmingGate.NO_CANDIDATE,)
        )

    candidates.sort(
        key=lambda code: (
            -contributions.get(ACTION_TOPICS[code], 0.0),
            TOPIC_ORDER.index(ACTION_TOPICS[code]),
        )
    )

    first_blocked: tuple[ArmingGate, ...] = ()
    for index, code in enumerate(candidates):
        blocked = _gates_for(
            code, wis=wis, state=state, preferences=preferences, config=config
        )
        if not blocked:
            return ArmingDecision(armed=True, action_code=code, blocked_by=())
        if index == 0:
            # Report the strongest candidate's gates: that is the one the user
            # would have received, so it is the actionable explanation.
            first_blocked = blocked

    return ArmingDecision(armed=False, action_code=None, blocked_by=first_blocked)
