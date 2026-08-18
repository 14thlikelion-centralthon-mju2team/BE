"""Engine configuration contracts.

The Backend ``ENGINE_CONFIG`` table is the single source of truth for all
engine parameters.  The AI server never stores or overrides config — it
validates the values received per-request and uses them as-is.
"""

from pydantic import Field

from app.contracts.base import ContractModel

# ──────────────────────────────────────────────────────────────────────────────
# Plan Engine Config (already exists in domain layer, re-defined here as the
# canonical contract; the domain EngineConfig re-exports from here in M1+).
# ──────────────────────────────────────────────────────────────────────────────


class PlanEngineConfig(ContractModel):
    """Plan Engine runtime configuration passed per-request by Backend."""

    seed_fallback_minutes: int = Field(default=30, ge=0)
    arrival_buffer_default_minutes: int = Field(default=10, ge=0)
    traffic_buffer_default_minutes: int = Field(default=5, ge=0)
    rain_threshold_percent: int = Field(default=60, ge=0, le=100)
    rain_extra_prep_minutes: int = Field(default=5, ge=0)
    calc_version: str = Field(min_length=1)


# ──────────────────────────────────────────────────────────────────────────────
# Personalization Engine Config
# ──────────────────────────────────────────────────────────────────────────────


class PersonalizationEngineConfig(ContractModel):
    """Personalization Engine runtime configuration.

    Controls EMA smoothing, guard-rails, and step limits for prep estimate
    adjustment (TRD §6).
    """

    prep_ema_alpha: float = Field(default=0.30, gt=0.0, le=1.0)
    late_weight: float = Field(default=1.50, gt=0.0)
    early_weight: float = Field(default=0.70, gt=0.0)
    max_step_minutes: int = Field(default=15, ge=1)
    cold_step_minutes: int = Field(default=20, ge=1)
    prep_floor_minutes: int = Field(default=10, ge=0)
    prep_ceiling_ratio: float = Field(default=2.0, gt=0.0)
    model_version: str = Field(min_length=1)


# ──────────────────────────────────────────────────────────────────────────────
# Wellness Engine Config
# ──────────────────────────────────────────────────────────────────────────────


class WellnessEngineConfig(ContractModel):
    """Wellness Engine runtime configuration.

    Controls WIS weight distribution and band thresholds (TRD §7).
    WIS is NOT a health score — it is a notification priority value only.
    """

    wis_weight_uv: float = Field(default=0.35, ge=0.0, le=1.0)
    wis_weight_pm: float = Field(default=0.25, ge=0.0, le=1.0)
    wis_weight_temp: float = Field(default=0.20, ge=0.0, le=1.0)
    wis_weight_outdoor: float = Field(default=0.20, ge=0.0, le=1.0)
    interest_boost_max: float = Field(default=1.25, gt=0.0)
    outdoor_cap_minutes: int = Field(default=120, ge=0)
    wis_band_card: int = Field(default=40, ge=0, le=100)
    wis_band_event: int = Field(default=70, ge=0, le=100)
    weight_version: str = Field(min_length=1)
