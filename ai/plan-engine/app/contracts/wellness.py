"""Wellness Engine contract models.

Defines the input/output schema for WIS (event wellness priority), action
selection, and environmental load normalization (TRD §7).  M0 freezes this
contract; M3 implements the actual scoring and action-selection logic.

Key rules documented in this contract:
- WIS is a notification priority value, NOT a health score (absolute rule 3).
- Actions are capped at 3 per event.
- No free-form LLM text — only approved action codes and structured reasons.
- Missing environment data is not an error; it results in degraded output.
- Sensitive prep items are never recommended by the engine.
"""

from pydantic import Field, field_validator

from app.contracts.base import ContractModel
from app.contracts.common import WellnessBand, WellnessTopic
from app.contracts.config import WellnessEngineConfig
from app.domain.plan_engine.enums import PrepActionType, PrepSourceType
from app.domain.plan_engine.models import EnvironmentSnapshot

CONTRACT_VERSION = "m0-v1"

MAX_WELLNESS_ACTIONS = 3

# ──────────────────────────────────────────────────────────────────────────────
# Input models
# ──────────────────────────────────────────────────────────────────────────────


class PrepItemSnapshot(ContractModel):
    """Prep item already in the user's checklist (shared shape with plan)."""

    item_id: str = Field(min_length=1)
    item_name: str = Field(min_length=1)
    action_type: PrepActionType
    source_type: PrepSourceType
    applied_minutes: int = Field(default=0, ge=0)
    is_sensitive: bool = False


class WellnessPreference(ContractModel):
    """Per-topic user preference for wellness notifications."""

    wellness_topic: WellnessTopic
    is_enabled: bool
    remind_interval_minutes: int | None = Field(default=None, ge=1)
    daily_event_cap: int = Field(default=1, ge=0)


class WellnessInput(ContractModel):
    """Full input to the wellness engine for a single event evaluation."""

    environment: EnvironmentSnapshot | None = None
    estimated_outdoor_minutes: int | None = Field(default=None, ge=0)
    user_preferences: list[WellnessPreference] = Field(default_factory=list)
    existing_prep_items: list[PrepItemSnapshot] = Field(default_factory=list)
    config: WellnessEngineConfig


# ──────────────────────────────────────────────────────────────────────────────
# Output models
# ──────────────────────────────────────────────────────────────────────────────


class NormalizedWellnessLoads(ContractModel):
    """Normalized environmental load factors (0.0~1.0 each)."""

    uv_load: float = Field(ge=0.0, le=1.0)
    pm_load: float = Field(ge=0.0, le=1.0)
    thermal_load: float = Field(ge=0.0, le=1.0)
    outdoor_load: float = Field(ge=0.0, le=1.0)
    interest_multiplier: float = Field(ge=0.0)


class WellnessAction(ContractModel):
    """A single wellness action recommendation.

    - ``action_code`` must come from an approved set (no free-form text).
    - ``action_label`` is the user-facing display string (template-based).
    - ``display_rank`` determines ordering (1 = highest priority).
    - ``reason`` is a structured explanation, not LLM-generated.
    """

    wellness_topic: WellnessTopic
    action_code: str = Field(min_length=1)
    action_label: str = Field(min_length=1)
    display_rank: int = Field(ge=1, le=MAX_WELLNESS_ACTIONS)
    reason: str = Field(min_length=1)


class WellnessOutput(ContractModel):
    """Result of the wellness engine evaluation.

    ``wis_score`` and ``wis_band`` may be None if environment data is
    insufficient — this is a ``degraded`` condition, not an error.
    """

    wis_score: int | None = Field(default=None, ge=0, le=100)
    wis_band: WellnessBand | None = None
    normalized_loads: NormalizedWellnessLoads | None = None
    actions: list[WellnessAction] = Field(default_factory=list)
    event_armed: bool
    weight_version: str = Field(min_length=1)
    contract_version: str = Field(default=CONTRACT_VERSION, min_length=1)
    degraded: list[str] = Field(default_factory=list)

    @field_validator("actions")
    @classmethod
    def max_three_actions(cls, v: list[WellnessAction]) -> list[WellnessAction]:
        if len(v) > MAX_WELLNESS_ACTIONS:
            raise ValueError(f"actions must contain at most {MAX_WELLNESS_ACTIONS} items")
        return v
