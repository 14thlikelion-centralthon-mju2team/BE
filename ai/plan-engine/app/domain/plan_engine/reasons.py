"""Reason data for every minute value that shaped the plan (prompt §13).

The engine passes already-rendered text so a value and its explanation are
produced in one place. User-facing copy can still be re-templated by Spring or
the product team.
"""

from app.domain.plan_engine.models import PlanReason


def build_reasons(
    *,
    prep_source: str,
    prep_text: str,
    routine_text: str,
    travel_minutes: int,
    route_source: str,
    traffic_buffer_text: str,
    arrival_buffer_text: str,
    extra_prep_text: str,
    rain_applied: bool,
) -> list[PlanReason]:
    return [
        PlanReason(field="estimatedPrepMinutes", source=prep_source, text=prep_text),
        PlanReason(field="personalRoutineMinutes", source="prepRule", text=routine_text),
        PlanReason(
            field="travelMinutes",
            source=route_source,
            text=f"선택 경로 이동 시간 {travel_minutes}분을 사용했습니다.",
        ),
        PlanReason(field="trafficBufferMinutes", source="config", text=traffic_buffer_text),
        PlanReason(field="arrivalBufferMinutes", source="config", text=arrival_buffer_text),
        PlanReason(
            field="extraPrepMinutes",
            source="environment" if rain_applied else "config",
            adjusted=rain_applied,
            text=extra_prep_text,
        ),
    ]
