"""Shared enums for all engine contracts.

Enum string values MUST match the Spring Boot Backend DTOs exactly.
If a discrepancy is found, do not resolve it unilaterally — document the
difference and get confirmation from the Backend tech lead.
"""

from enum import StrEnum

# ──────────────────────────────────────────────────────────────────────────────
# Plan Engine enums (re-exported from existing domain layer)
# ──────────────────────────────────────────────────────────────────────────────
from app.domain.plan_engine.enums import (  # noqa: F401
    AnchorMode,
    DegradedReason,
    PredictionConfidence,
    PrepActionType,
    PrepSourceType,
)

# ──────────────────────────────────────────────────────────────────────────────
# Personalization Engine enums
# ──────────────────────────────────────────────────────────────────────────────


class DelayCause(StrEnum):
    """Root cause of a late arrival, attributed by the personalization engine."""

    PREP_LATE = "prep_late"
    PREP_OVERRUN = "prep_overrun"
    DEPART_LATE = "depart_late"
    TRAFFIC = "traffic"
    EXTERNAL = "external"
    UNKNOWN = "unknown"


class AdjustmentKnob(StrEnum):
    """Which parameter the personalization engine recommends adjusting."""

    PREP_ESTIMATE = "prep_estimate"
    NOTIFICATION_LEAD = "notification_lead"
    DEPARTURE_LEAD = "departure_lead"
    TRAFFIC_BUFFER = "traffic_buffer"
    NONE = "none"


# ──────────────────────────────────────────────────────────────────────────────
# Wellness Engine enums
# ──────────────────────────────────────────────────────────────────────────────


class WellnessTopic(StrEnum):
    """Environmental wellness factor tracked by the wellness engine."""

    UV = "uv"
    PM = "pm"
    TEMP = "temp"
    RAIN = "rain"
    HYDRATION = "hydration"


class WellnessBand(StrEnum):
    """WIS score band used for notification priority routing."""

    LOW = "low"
    MID = "mid"
    HIGH = "high"


# ──────────────────────────────────────────────────────────────────────────────
# Golden Fixture engine type
# ──────────────────────────────────────────────────────────────────────────────


class EngineType(StrEnum):
    """Target engine for a golden fixture case."""

    PLAN = "plan"
    PERSONALIZATION = "personalization"
    WELLNESS = "wellness"
