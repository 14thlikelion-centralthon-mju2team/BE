from datetime import datetime
from zoneinfo import ZoneInfo

from app.domain.plan_engine.enums import AnchorMode
from app.schemas.plan import (
    EngineConfig,
    EventSnapshot,
    PlanInput,
    PrepEstimate,
    RouteSnapshot,
)

SEOUL = ZoneInfo("Asia/Seoul")


def full_config() -> EngineConfig:
    """Config with every field explicitly set, so no CONFIG_FALLBACK is raised."""
    return EngineConfig(
        seed_fallback_minutes=30,
        rain_threshold_percent=60,
        rain_extra_prep_minutes=5,
        arrival_buffer_default_minutes=10,
        traffic_buffer_default_minutes=5,
    )


def make_input(**overrides: object) -> PlanInput:
    values: dict[str, object] = {
        "now": datetime(2026, 8, 20, 12, tzinfo=SEOUL),
        "event": EventSnapshot(
            starts_at=datetime(2026, 8, 20, 14, tzinfo=SEOUL),
            anchor_mode=AnchorMode.ARRIVE_BY,
        ),
        "prep_estimate": PrepEstimate(
            estimated_minutes=30,
            source="initial_seed",
            sample_count=0,
        ),
        "arrival_buffer_minutes": 10,
        "traffic_buffer_minutes": 5,
        "selected_route": RouteSnapshot(
            route_id="route-1",
            total_minutes=40,
            walk_minutes=15,
            source="odsay",
        ),
        "environment": None,
        "prep_items": [],
        "config": full_config(),
    }
    values.update(overrides)
    return PlanInput.model_validate(values)
