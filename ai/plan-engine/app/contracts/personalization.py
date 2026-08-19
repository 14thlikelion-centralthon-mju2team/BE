"""Personalization Engine contract models.

Defines the input/output schema for causal attribution and prep estimate
adjustment (TRD §6).  M0 froze this contract; M2 implements the actual
cause-separation and EMA logic.

The personalization engine receives a completed event's planned vs actual
execution data, determines the root cause of any delay, and recommends which
parameter to adjust and by how much.

M2 additions are **additive only** (contract doc §10): every new input field is
optional with a default, and every new output field has a default, so an M0
client keeps working.  The additions and why TRD §6 requires them:

===========================  ===========================================
field                        rule it makes computable
===========================  ===========================================
``currentEstimate.seedMinutes``       ceiling guard-rail ``P ≤ seed × 2``
``currentEstimate.coldStartAdjusted`` "첫 명확한 실패만 1회" (§6.2)
``actual.actualPrepFinishedAt``       separates ``prep_overrun`` from
                                      ``depart_late`` (§6.2) — without it the
                                      two causes are algebraically identical
``actual.resultConfidence``           geo sample trust gate (§6.1)
``outcome.learningReverted``          revert excludes the sample (§6.4)
``outcome.eventModifiedAfterPlan``    "일정 유효" filter (§6.1) → ``external``
``causeConfidence`` / ``candidates``  EVENT_DELAY_REASON.confidence and its
                                      composite PK (multiple causes) (§6.2)
``exclusionReasons``                  why a sample was dropped (§16 metrics)
``degraded``                          seed fallback and missing-signal record
===========================  ===========================================
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

    # M2: EVENT_EXECUTION has no prep-finished column yet (ERD v3).  When the
    # Backend can supply it, the engine can tell "prep ran long" apart from
    # "prep was done but the user lingered".  Absent → degraded record.
    actual_prep_finished_at: datetime | None = None
    #: Geofence decision confidence for ``result_source='geo'`` (TRD §9.2).
    result_confidence: float | None = Field(default=None, ge=0.0, le=1.0)

    _tz_prep = field_validator("actual_prep_started_at")(require_aware_datetime)
    _tz_prep_done = field_validator("actual_prep_finished_at")(require_aware_datetime)
    _tz_depart = field_validator("actual_departed_at")(require_aware_datetime)
    _tz_arrive = field_validator("actual_arrived_at")(require_aware_datetime)


class EventOutcome(ContractModel):
    """High-level outcome classification for the event."""

    arrival_result: str = Field(min_length=1)
    rush_assessment: str | None = None
    auto_manage_excluded: bool = False

    # M2 additions
    #: True when the user reverted a previous adjustment for this sample —
    #: the sample is permanently excluded so the same correction cannot
    #: reappear on the next tick (TRD §6.4).
    learning_reverted: bool = False
    #: True when the event was deleted or its time changed after the plan was
    #: computed, which invalidates the planned baseline (TRD §6.1).
    event_modified_after_plan: bool = False


class CurrentPrepEstimate(ContractModel):
    """The user's current prep time estimate before this adjustment."""

    estimated_minutes: float = Field(ge=0.0)
    sample_count: int = Field(ge=0)
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    model_version: str = Field(min_length=1)

    # M2 additions
    #: ``USER_SETTING.initial_prep_minutes``.  None means "잘 모르겠어요" and
    #: the engine falls back to ``config.seedFallbackMinutes`` (TRD §6.2).
    seed_minutes: float | None = Field(default=None, ge=0.0)
    #: True when the one-off cold-start correction was already applied.
    cold_start_adjusted: bool = False


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


class CauseCandidate(ContractModel):
    """One attributed cause and its share of the observed delay.

    ``EVENT_DELAY_REASON`` has a ``(event_id, reason_code)`` composite PK, so
    the Backend may persist every candidate.  Only ``adjustedKnob`` acts
    (TR-05: one observation turns exactly one knob).
    """

    cause: DelayCause
    confidence: float = Field(ge=0.0, le=1.0)
    signal_minutes: float


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

    # M2 additions
    cause_confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    candidates: list[CauseCandidate] = Field(default_factory=list)
    exclusion_reasons: list[str] = Field(default_factory=list)
    degraded: list[str] = Field(default_factory=list)
