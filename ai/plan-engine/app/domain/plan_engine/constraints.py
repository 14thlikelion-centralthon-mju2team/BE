from datetime import datetime

from app.domain.plan_engine.models import PlanReason


def evaluate_feasibility(
    *,
    now: datetime,
    event_starts_at: datetime,
    prep_start_at: datetime,
    recommended_depart_at: datetime,
    target_arrive_at: datetime,
) -> tuple[bool, list[PlanReason]]:
    """Judge whether the computed plan can still be executed.

    Timestamps are never clamped to ``now``: an impossible plan keeps its
    computed values and explains why it is impossible (prompt §14).
    """
    failures: list[str] = []
    if prep_start_at < now:
        failures.append("현재 시각이 계산된 준비 시작 시각을 지났습니다.")
    if recommended_depart_at < now:
        failures.append("현재 시각이 계산된 권장 출발 시각을 지났습니다.")
    if target_arrive_at > event_starts_at:
        failures.append("계산된 도착 시각이 일정 시작 시각보다 늦습니다.")

    if not failures:
        return True, [
            PlanReason(
                field="feasible",
                source="constraint",
                text="현재 입력 조건에서 계획을 실행할 수 있습니다.",
            )
        ]

    return False, [
        PlanReason(field="feasible", source="constraint", text=message) for message in failures
    ]
