"""Personalization Engine contract models.

Defines the input/output schema for causal attribution and prep estimate
adjustment (TRD §6).  M0 freezes this contract; M2 implements the actual
cause-separation and EMA logic without modifying the schema.

The personalization engine receives a completed event's planned vs actual
execution data, determines the root cause of any delay, and recommends which
parameter to adjust and by how much.
"""

from datetime import datetime

from pydantic import Field, field_validator

from app.contracts.base import ContractModel, require_aware_datetime
from app.contracts.common import AdjustmentKnob, DelayCause
from app.contracts.config import PersonalizationEngineConfig

CONTRACT_VERSION = "m0-v1"

# ──────────────────────────────────────────────────────────────────────────────
# Input models
# ──────────────────────────────────────────────────────────────────────────────


class PlannedExecutionSnapshot(ContractModel):
    """What the plan engine originally computed for this event."""

    prep_start_at: datetime
    recommended_depart_at: datetime
    target_arrive_at: datetime
    estimated_prep_minutes: int = Field(ge=0)
    travel_minutes: int = Field(ge=0)
    traffic_buffer_minutes: int = Field(ge=0)

    _tz_prep = field_validator("prep_start_at")(require_aware_datetime)
    _tz_depart = field_validator("recommended_depart_at")(require_aware_datetime)
    _tz_arrive = field_validator("target_arrive_at")(require_aware_datetime)


class ActualExecutionSnapshot(ContractModel):
    """What actually happened — recorded by the user or inferred from sensors."""

    actual_prep_started_at: datetime | None = None
    actual_departed_at: datetime | None = None
    actual_arrived_at: datetime | None = None
    result_source: str | None = None
    clock_skew_seconds: int | None = None

    _tz_prep = field_validator("actual_prep_started_at")(require_aware_datetime)
    _tz_depart = field_validator("actual_departed_at")(require_aware_datetime)
    _tz_arrive = field_validator("actual_arrived_at")(require_aware_datetime)


class EventOutcome(ContractModel):
    """High-level outcome classification for the event."""

    arrival_result: str = Field(min_length=1)
    rush_assessment: str | None = None
    auto_manage_excluded: bool = False


class CurrentPrepEstimate(ContractModel):
    """The user's current prep time estimate before this adjustment."""

    estimated_minutes: float = Field(ge=0.0)
    sample_count: int = Field(ge=0)
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    model_version: str = Field(min_length=1)


class PersonalizationInput(ContractModel):
    """Full input to the personalization engine for a single completed event."""

    event_id: str = Field(min_length=1)
    planned: PlannedExecutionSnapshot
    actual: ActualExecutionSnapshot
    outcome: EventOutcome
    current_estimate: CurrentPrepEstimate
    config: PersonalizationEngineConfig


# ──────────────────────────────────────────────────────────────────────────────
# Output models
# ──────────────────────────────────────────────────────────────────────────────


class PersonalizationOutput(ContractModel):
    """Result of the personalization engine's cause-separation analysis."""

    cause: DelayCause
    adjusted_knob: AdjustmentKnob
    previous_value: float | None = None
    new_value: float | None = None
    adjustment_reason: str | None = None
    excluded_from_learning: bool
    model_version: str = Field(min_length=1)
    contract_version: str = Field(default=CONTRACT_VERSION, min_length=1)
