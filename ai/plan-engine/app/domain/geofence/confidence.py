"""도착 판정 신뢰도 공식 (TRD §9.2).

```
confidence = 0.5
           + 0.20 체류 조건 충족
           + 0.15 진입 시 수평 정확도 < 50m
           + 0.15 진입 시각이 예상 도착 ±20분 이내
           − 0.30 경계 진동 (60초 내 진입/이탈 반복)

≥ 0.6 자동 확정 · 0.4~0.6 조용한 확인 요청 · < 0.4 unresolved
```

가점 셋이 모두 붙으면 1.0, 하나도 못 붙으면 0.5, 진동까지 겹치면 0.2입니다. 계수를 그대로 두면
경계는 이렇게 갈립니다.

| 관측 | 신뢰도 | 판정 |
|---|---|---|
| 아무 가점 없음 | 0.50 | 조용한 확인 |
| 가점 하나라도 (체류 / 정확도 / 시각) | 0.65~0.70 | 자동 확정 |
| 진동만 | 0.20 | unresolved |
| 진동 + 가점 하나 | 0.35~0.40 | unresolved 또는 조용한 확인 |
| 진동 + 가점 둘 | 0.50~0.55 | 조용한 확인 |
| 진동 + 가점 셋 | 0.70 | 자동 확정 |

즉 **아무 근거가 없는 관측도 0.5에서 출발하므로 조용한 확인까지는 가고**, 가점이 하나라도 붙으면
자동 확정선을 넘습니다. 진동이 끼면 가점 셋을 다 모아야 자동 확정입니다.

경계 진동은 억제하지 않고 신뢰도를 깎습니다 — **진동 자체가 "판정이 불확실하다"는 정보**입니다.

``ConfidenceBreakdown``이 각 항의 기여도를 그대로 돌려주므로, 튜닝할 때 어떤 항이 판정을 뒤집었는지
숫자로 볼 수 있습니다. 실기기 실측(§17.4 자동 확정률 ≥ 70% · 오판 ≤ 10%)에서 계수를 조정할 때
쓰는 표가 그것입니다.
"""

from dataclasses import dataclass
from datetime import datetime

from app.contracts.config import GeofenceConfig
from app.domain.geofence.enums import ArrivalDecision, DestinationKind


@dataclass(frozen=True)
class ArrivalObservation:
    """기기가 올려준 도착 판정 관측 하나.

    ``entered_at``과 ``expected_arrival_at``은 timezone-aware여야 합니다. 엔진은 현재 시각을
    읽지 않고 두 값의 차이만 씁니다. naive datetime이 섞이면 뺄셈이 ``TypeError``로 죽거나,
    둘 다 naive면 조용히 잘못된 시각차를 계산합니다 — 그래서 경계에서 막습니다.
    """

    #: 리전 안에서 머문 시간(초).
    dwell_seconds: float
    #: 진입 시점의 수평 정확도(m).  None이면 기기가 주지 않았다는 뜻이고 가점을 받지 못한다.
    horizontal_accuracy_meters: float | None
    entered_at: datetime
    expected_arrival_at: datetime
    #: 진동 창 안에서 관측된 진입/이탈 전이 횟수.  2회 이상이면 진동으로 본다.
    transitions_in_window: int = 1
    destination_kind: DestinationKind = DestinationKind.UNKNOWN

    def __post_init__(self) -> None:
        for name in ("entered_at", "expected_arrival_at"):
            value: datetime = getattr(self, name)
            if value.tzinfo is None or value.utcoffset() is None:
                raise ValueError(f"{name} must be timezone-aware")


@dataclass(frozen=True)
class ConfidenceBreakdown:
    """신뢰도와 각 항의 기여도. 튜닝용."""

    confidence: float
    decision: ArrivalDecision
    base: float
    dwell: float
    accuracy: float
    timing: float
    oscillation: float
    #: 판정에 쓰인 목적지 반경(m).
    radius_meters: int

    @property
    def auto_confirmed(self) -> bool:
        return self.decision is ArrivalDecision.AUTO_CONFIRM


def destination_radius_meters(
    kind: DestinationKind,
    config: GeofenceConfig,
) -> int:
    if kind is DestinationKind.GROUND_POI:
        return config.destination_radius_ground_meters
    if kind is DestinationKind.SUBWAY_OR_COMPLEX:
        return config.destination_radius_complex_meters
    return config.destination_radius_default_meters


def _minutes_between(later: datetime, earlier: datetime) -> float:
    return (later - earlier).total_seconds() / 60.0


def compute_arrival_confidence(
    observation: ArrivalObservation,
    config: GeofenceConfig,
) -> ConfidenceBreakdown:
    """§9.2 공식을 그대로 계산하고 각 항을 함께 돌려준다."""
    dwell_met = observation.dwell_seconds >= config.dwell_seconds
    accuracy_met = (
        observation.horizontal_accuracy_meters is not None
        and observation.horizontal_accuracy_meters < config.accuracy_good_meters
    )
    timing_met = (
        abs(_minutes_between(observation.entered_at, observation.expected_arrival_at))
        <= config.timing_window_minutes
    )
    oscillating = observation.transitions_in_window >= 2

    dwell = config.dwell_bonus if dwell_met else 0.0
    accuracy = config.accuracy_bonus if accuracy_met else 0.0
    timing = config.timing_bonus if timing_met else 0.0
    oscillation = -config.oscillation_penalty if oscillating else 0.0

    raw = config.base_confidence + dwell + accuracy + timing + oscillation
    confidence = round(max(0.0, min(1.0, raw)), 4)

    if confidence >= config.auto_confirm_confidence:
        decision = ArrivalDecision.AUTO_CONFIRM
    elif confidence >= config.quiet_confirm_confidence:
        decision = ArrivalDecision.QUIET_CONFIRM
    else:
        decision = ArrivalDecision.UNRESOLVED

    return ConfidenceBreakdown(
        confidence=confidence,
        decision=decision,
        base=config.base_confidence,
        dwell=dwell,
        accuracy=accuracy,
        timing=timing,
        oscillation=oscillation,
        radius_meters=destination_radius_meters(observation.destination_kind, config),
    )


def is_unresolved_by_timeout(
    minutes_since_event_start: float,
    config: GeofenceConfig,
) -> bool:
    """일정 시작 +N분 무신호면 unresolved로 전환한다 (§9.2 · ``UNRESOLVED_AFTER_MIN``).

    전환 후에는 홈 카드에서 1탭 확인을 받습니다 — 푸시가 아닙니다.
    """
    return minutes_since_event_start >= config.unresolved_after_minutes
