"""Domain models for the plan engine.

These models are part of the pure domain layer: no FastAPI, DB, HTTP client,
clock, or environment access. The API layer re-exports them from
``app.schemas.plan`` so the transport layer never owns the calculation
contract.
"""

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.domain.plan_engine.enums import (
    AnchorMode,
    DegradedReason,
    PredictionConfidence,
    PrepActionType,
    PrepSourceType,
)


def to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class CamelModel(BaseModel):
    """Base model exposing camelCase JSON while keeping snake_case in Python.

    ``extra="forbid"`` is intentional: an unknown field usually means the
    caller and the engine disagree about the contract, and silently ignoring
    it would produce a plan computed from partially understood input.
    """

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


def require_aware(value: datetime | None) -> datetime | None:
    if value is not None and (value.tzinfo is None or value.utcoffset() is None):
        raise ValueError("datetime must include a timezone offset")
    return value


class EventSnapshot(CamelModel):
    starts_at: datetime
    anchor_mode: AnchorMode
    fixed_depart_at: datetime | None = None

    _starts_at_aware = field_validator("starts_at")(require_aware)
    _fixed_depart_at_aware = field_validator("fixed_depart_at")(require_aware)

    @model_validator(mode="after")
    def validate_anchor(self) -> "EventSnapshot":
        if self.anchor_mode is AnchorMode.DEPART_AT and self.fixed_depart_at is None:
            raise ValueError("fixed_depart_at is required when anchor_mode is depart_at")
        return self


class PrepEstimate(CamelModel):
    estimated_minutes: int = Field(ge=0)
    source: str = Field(min_length=1)
    sample_count: int = Field(default=0, ge=0)


class RouteSnapshot(CamelModel):
    route_id: str = Field(min_length=1)
    total_minutes: int = Field(ge=0)
    walk_minutes: int = Field(default=0, ge=0)
    source: str = Field(min_length=1)
    is_stale: bool = False


class EnvironmentSnapshot(CamelModel):
    precipitation_probability: int | None = Field(default=None, ge=0, le=100)
    feels_like_celsius: float | None = None
    observed_at: datetime | None = None

    _observed_at_aware = field_validator("observed_at")(require_aware)


class PrepItemSnapshot(CamelModel):
    item_id: str = Field(min_length=1)
    item_name: str = Field(min_length=1)
    action_type: PrepActionType
    source_type: PrepSourceType
    applied_minutes: int = Field(default=0, ge=0)
    is_sensitive: bool = False


class EngineConfig(CamelModel):
    seed_fallback_minutes: int = Field(default=30, ge=0)
    rain_threshold_percent: int = Field(default=60, ge=0, le=100)
    rain_extra_prep_minutes: int = Field(default=5, ge=0)
    # The two defaults below exist so Spring can pass the full ENGINE_CONFIG row.
    # The engine always uses the explicit per-request buffers in PlanInput, so
    # these values never take part in the calculation.
    arrival_buffer_default_minutes: int = Field(default=10, ge=0)
    traffic_buffer_default_minutes: int = Field(default=5, ge=0)


#: Config fields the calculation actually reads. Only these can degrade a plan.
ENGINE_USED_CONFIG_FIELDS = frozenset(
    {
        "seed_fallback_minutes",
        "rain_threshold_percent",
        "rain_extra_prep_minutes",
    }
)


class PlanInput(CamelModel):
    now: datetime
    event: EventSnapshot
    # Omitting the field and sending null both mean "no observed estimate yet".
    prep_estimate: PrepEstimate | None = None
    arrival_buffer_minutes: int = Field(ge=0)
    traffic_buffer_minutes: int = Field(ge=0)
    selected_route: RouteSnapshot
    environment: EnvironmentSnapshot | None = None
    prep_items: list[PrepItemSnapshot] = Field(default_factory=list)
    config: EngineConfig

    _now_aware = field_validator("now")(require_aware)


class PlanBreakdown(CamelModel):
    """Minutes actually applied to the returned timestamps.

    A buffer that the anchor mode does not use is reported as 0 so the three
    timestamps can be reconstructed from this breakdown alone.
    """

    estimated_prep_minutes: int = Field(ge=0)
    extra_prep_minutes: int = Field(ge=0)
    personal_routine_minutes: int = Field(ge=0)
    travel_minutes: int = Field(ge=0)
    traffic_buffer_minutes: int = Field(ge=0)
    arrival_buffer_minutes: int = Field(ge=0)


class PlanReason(CamelModel):
    field: str
    source: str
    adjusted: bool = False
    text: str


class PlanChecklistItem(CamelModel):
    item_name: str
    action_type: PrepActionType
    source_type: PrepSourceType
    applied_minutes: int = Field(default=0, ge=0)
    is_sensitive: bool = False
    reason: str | None = None


class PlanOutput(CamelModel):
    prep_start_at: datetime
    recommended_depart_at: datetime
    target_arrive_at: datetime
    breakdown: PlanBreakdown
    reasons: list[PlanReason]
    checklist: list[PlanChecklistItem]
    feasible: bool
    prediction_confidence: PredictionConfidence
    degraded: list[DegradedReason]
    calc_version: str = Field(min_length=1)

    @field_validator("prep_start_at", "recommended_depart_at", "target_arrive_at")
    @classmethod
    def output_datetime_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("datetime must include a timezone offset")
        return value
