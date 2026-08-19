"""웰니스 보조 지표 4종 (TRD §16.2 · PRD §4.4, §24.4).

```
행동 완료율   = PLAN_WELLNESS_ACTION completed / proposed
이벤트 반응률 = WELLNESS_EVENT_SCHEDULE response ∈ (completed, snoozed) / sent
적합률       = user_rating='useful' / rating 수집분
커버리지     = WIS 생성 일정 / estimated_outdoor_minutes > 0 인 일정
```

네 지표 모두 **분모가 0이면 None**입니다. 0건 중 0건은 0%가 아니라 측정 불가이고, 이 둘을 같은
숫자로 보고하면 "제안을 아예 안 한 주"가 "제안했지만 아무도 안 한 주"와 구별되지 않습니다.

반응률에 ``snoozed``를 넣는 것은 §16.2 정의 그대로입니다. 미루기는 무시가 아니라 반응입니다 —
알림을 봤고, 지금은 아니라고 답한 것이므로 알림이 닿았다는 증거가 됩니다. ``stop_today``와
``ignored``는 반응으로 세지 않습니다.

적합률은 ``user_rating``이 **유일한 원천**입니다(§7.4). 완료했다는 사실을 유용했다는 뜻으로
바꿔 세면 안 됩니다.
"""

from dataclasses import dataclass

from pydantic import Field, model_validator

from app.domain.plan_engine.models import CamelModel


class WellnessMetricInput(CamelModel):
    """한 기간(주차 등)의 원시 카운트.

    분자 ≤ 분모를 모델 수준에서 강제합니다. 100%를 넘는 비율은 지표가 아니라 조인 오류이고,
    그것이 성공 지표로 보이면 아무도 눈치채지 못합니다.

    **집계 조인은 제안 시점을 기준으로 귀속해야 이 불변식이 성립합니다.** 주 경계에서 "지난주에
    제안하고 이번 주에 완료한" 행동을 완료 시점으로 세면 완료가 제안을 넘길 수 있습니다.
    SQL 은 ``PLAN_WELLNESS_ACTION.created_at`` 기준으로 주차를 나누고 완료 여부는 같은 행에서
    읽어야 합니다.
    """

    #: PLAN_WELLNESS_ACTION
    proposed_actions: int = Field(default=0, ge=0)
    completed_actions: int = Field(default=0, ge=0)
    #: WELLNESS_EVENT_SCHEDULE
    events_sent: int = Field(default=0, ge=0)
    events_completed: int = Field(default=0, ge=0)
    events_snoozed: int = Field(default=0, ge=0)
    #: user_rating 수집분
    ratings_collected: int = Field(default=0, ge=0)
    ratings_useful: int = Field(default=0, ge=0)
    #: 커버리지 — 야외 노출이 있는 일정과 그중 WIS가 생성된 일정
    outdoor_events: int = Field(default=0, ge=0)
    wis_generated_events: int = Field(default=0, ge=0)

    @model_validator(mode="after")
    def numerators_cannot_exceed_denominators(self) -> "WellnessMetricInput":
        violations = [
            name
            for name, numerator, denominator in (
                ("completedActions", self.completed_actions, self.proposed_actions),
                (
                    "eventsCompleted+eventsSnoozed",
                    self.events_completed + self.events_snoozed,
                    self.events_sent,
                ),
                ("ratingsUseful", self.ratings_useful, self.ratings_collected),
                ("wisGeneratedEvents", self.wis_generated_events, self.outdoor_events),
            )
            if numerator > denominator
        ]
        if violations:
            raise ValueError(
                "numerator exceeds denominator — this is a join error, not a metric: "
                + ", ".join(violations)
            )
        return self


@dataclass(frozen=True)
class WellnessMetrics:
    """네 비율.  전부 None 가능 — 측정 불가와 0%를 구분한다."""

    action_completion_rate: float | None
    event_response_rate: float | None
    usefulness_rate: float | None
    coverage_rate: float | None


def _ratio(numerator: int, denominator: int) -> float | None:
    if denominator <= 0:
        return None
    return round(numerator / denominator, 4)


def compute_wellness_metrics(counts: WellnessMetricInput) -> WellnessMetrics:
    return WellnessMetrics(
        action_completion_rate=_ratio(counts.completed_actions, counts.proposed_actions),
        event_response_rate=_ratio(
            counts.events_completed + counts.events_snoozed, counts.events_sent
        ),
        usefulness_rate=_ratio(counts.ratings_useful, counts.ratings_collected),
        coverage_rate=_ratio(counts.wis_generated_events, counts.outdoor_events),
    )
