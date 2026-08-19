"""북극성 지표 — "늦지 않고 여유 있게 도착한 주간 일정 수" (TRD §16.2 · PRD §24.1).

```
ok = arrivalResult='on_time'
     ∧ 극한 알림 ≤ 1
     ∧ |Δdepart| ≤ DEPART_TOLERANCE
     ∧ rushAssessment ≠ 'rushed'
     ∧ margin ≤ EARLY_MIN
```

다섯 조건이 모두 참이어야 1건으로 셉니다. **네 개의 임계값은 전부 원격 설정**이라 쿼리를 고치지
않고 재집계할 수 있어야 합니다(TR-06).

마지막 조건이 이 지표의 핵심입니다. 정시 도착만 세면 "두 시간 일찍 도착해서 앉아 기다린 날"도
성공으로 잡힙니다. 그건 여유가 아니라 다른 방향의 실패이므로(PRD §8.2), 과도하게 이른 도착은
북극성에서 제외합니다.

실패 사유를 함께 돌려주므로, 주간 집계에서 무엇이 북극성을 깎았는지 바로 볼 수 있습니다.
"""

from dataclasses import dataclass, field

from pydantic import Field

from app.domain.plan_engine.models import CamelModel


class NorthStarConfig(CamelModel):
    """부록 A.1의 북극성 임계값 4종."""

    #: ``DEPART_TOLERANCE_MIN`` — 계획 범위 내 출발 허용 폭.
    depart_tolerance_minutes: int = Field(default=10, ge=0)
    #: ``EARLY_MIN`` — 이보다 일찍 도착하면 과도한 조기 도착.
    early_minutes: int = Field(default=30, ge=0)
    #: 극한 알림 허용 횟수.
    max_critical_alerts: int = Field(default=1, ge=0)
    #: 성공으로 인정하는 ``arrival_result``.
    ok_arrival_result: str = Field(default="on_time", min_length=1)


class NorthStarInput(CamelModel):
    """완료된 일정 하나의 북극성 판정 입력."""

    event_id: str = Field(min_length=1)
    arrival_result: str = Field(min_length=1)
    critical_alert_count: int = Field(default=0, ge=0)
    #: ``actualDepartedAt − recommendedDepartAt`` (분, 부호 있음).
    depart_delta_minutes: float = 0.0
    #: 사용자 촉박 평가 (``EVENT_FEEDBACK.rush_assessment``).
    rush_assessment: str | None = None
    #: 일정 시작 − 실제 도착 (분).  클수록 일찍 도착했다는 뜻이다.
    margin_minutes: float | None = None


#: 북극성을 깎은 사유 코드.  ``list[str]``로 나가므로 추가는 non-breaking.
REASON_ARRIVAL = "arrival_not_on_time"
REASON_ALERTS = "too_many_critical_alerts"
REASON_DEPART = "depart_out_of_tolerance"
REASON_RUSHED = "rushed"
REASON_EARLY = "arrived_too_early"
REASON_MARGIN_UNKNOWN = "margin_unknown"


@dataclass(frozen=True)
class NorthStarOutcome:
    event_id: str
    ok: bool
    failed_reasons: tuple[str, ...] = field(default=())


def evaluate_north_star(
    row: NorthStarInput,
    config: NorthStarConfig,
) -> NorthStarOutcome:
    """일정 하나가 북극성에 드는지 판정하고, 아니라면 어느 조건이 막았는지 남긴다."""
    failed: list[str] = []

    if row.arrival_result != config.ok_arrival_result:
        failed.append(REASON_ARRIVAL)
    if row.critical_alert_count > config.max_critical_alerts:
        failed.append(REASON_ALERTS)
    if abs(row.depart_delta_minutes) > config.depart_tolerance_minutes:
        failed.append(REASON_DEPART)
    if row.rush_assessment == "rushed":
        failed.append(REASON_RUSHED)
    # margin 을 모르면 도착 시각을 모르는 것이다.  모르는 것을 성공으로 세지 않는다.
    if row.margin_minutes is None:
        failed.append(REASON_MARGIN_UNKNOWN)
    elif row.margin_minutes > config.early_minutes:
        failed.append(REASON_EARLY)

    unique_failed = tuple(dict.fromkeys(failed))
    return NorthStarOutcome(
        event_id=row.event_id, ok=not unique_failed, failed_reasons=unique_failed
    )


@dataclass(frozen=True)
class NorthStarAggregate:
    total_events: int
    ok_events: int
    #: 사유별 실패 건수.  주간 회고에서 무엇을 고칠지 정하는 근거가 된다.
    failed_by_reason: dict[str, int]

    @property
    def ok_ratio(self) -> float | None:
        """분모가 0이면 None.  0건 중 0건은 0%가 아니라 측정 불가다."""
        if self.total_events == 0:
            return None
        return round(self.ok_events / self.total_events, 4)


def aggregate_north_star(
    rows: list[NorthStarInput],
    config: NorthStarConfig,
) -> NorthStarAggregate:
    ok_count = 0
    failed_by_reason: dict[str, int] = {}
    for row in rows:
        outcome = evaluate_north_star(row, config)
        if outcome.ok:
            ok_count += 1
            continue
        for reason in outcome.failed_reasons:
            failed_by_reason[reason] = failed_by_reason.get(reason, 0) + 1

    return NorthStarAggregate(
        total_events=len(rows),
        ok_events=ok_count,
        failed_by_reason=dict(sorted(failed_by_reason.items())),
    )
