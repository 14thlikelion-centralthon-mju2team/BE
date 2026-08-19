"""Cause-separated correction entry point (TRD §6).

    decompose → qualify → attribute → route to one knob → sentence

Pure and total: every input that passed schema validation gets a 200-shaped
answer.  An unqualified sample is reported, not raised — the Backend needs the
exclusion codes for its guard-rail metrics (§16).
"""

from app.contracts.common import AdjustmentKnob, DelayCause
from app.contracts.config import PersonalizationEngineConfig
from app.contracts.personalization import (
    CauseCandidate,
    PersonalizationInput,
    PersonalizationOutput,
)
from app.domain.personalization_engine.adjustment import apply_adjustment
from app.domain.personalization_engine.attribution import attribute_cause
from app.domain.personalization_engine.eligibility import evaluate_eligibility
from app.domain.personalization_engine.enums import (
    ExclusionReason,
    PersonalizationDegraded,
    parse_arrival_result,
)
from app.domain.personalization_engine.observation import Observation, decompose
from app.domain.personalization_engine.reasons import (
    build_adjustment_reason,
    build_exclusion_reason,
)
from app.domain.personalization_engine.version import MODEL_VERSION

#: Config fields the calculation actually reads.  ``model_version`` is an echo
#: of the ENGINE_CONFIG row and takes no part in the result, so omitting it
#: cannot degrade anything.  Mirrors ``ENGINE_USED_CONFIG_FIELDS`` in M1.
PERSONALIZATION_USED_CONFIG_FIELDS = frozenset(
    {
        "prep_ema_alpha",
        "late_weight",
        "early_weight",
        "max_step_minutes",
        "cold_step_minutes",
        "prep_floor_minutes",
        "prep_ceiling_ratio",
        "seed_fallback_minutes",
        "cold_start_sample_threshold",
        "clock_skew_tolerance_seconds",
        "prep_outlier_max_minutes",
        "geo_min_confidence",
        "attribution_min_signal_minutes",
    }
)


def _dedupe(values: list[str]) -> list[str]:
    """Stable de-duplication so ``degraded`` reads as an ordered record."""
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value not in seen:
            seen.add(value)
            result.append(value)
    return result


def _signal_degraded(
    payload: PersonalizationInput,
    observation: Observation | None,
    config: PersonalizationEngineConfig,
) -> list[str]:
    """What was missing from the observation, before any adjustment runs."""
    degraded: list[str] = []
    if observation is not None:
        if observation.lingering_minutes is None:
            degraded.append(PersonalizationDegraded.PREP_FINISH_UNKNOWN.value)
        if observation.transit_error_minutes is None:
            degraded.append(PersonalizationDegraded.TRANSIT_UNKNOWN.value)
    if PERSONALIZATION_USED_CONFIG_FIELDS - payload.config.model_fields_set:
        degraded.append(PersonalizationDegraded.CONFIG_FALLBACK.value)
    return degraded


def _excluded_output(
    payload: PersonalizationInput,
    exclusions: list[ExclusionReason],
    degraded: list[str],
) -> PersonalizationOutput:
    # An event that moved is an external cause; every other failed filter means
    # the observation itself is not trustworthy, so no cause is claimed.
    cause = (
        DelayCause.EXTERNAL
        if ExclusionReason.EVENT_MODIFIED in exclusions
        else DelayCause.UNKNOWN
    )
    return PersonalizationOutput(
        cause=cause,
        adjusted_knob=AdjustmentKnob.NONE,
        previous_value=round(float(payload.current_estimate.estimated_minutes), 1),
        new_value=None,
        adjustment_reason=build_exclusion_reason(exclusions),
        excluded_from_learning=True,
        model_version=MODEL_VERSION,
        cause_confidence=None,
        candidates=[],
        exclusion_reasons=[reason.value for reason in exclusions],
        # No seed_fallback here: nothing was adjusted, so the seed took no part.
        degraded=_dedupe(degraded),
    )


def adjust(payload: PersonalizationInput) -> PersonalizationOutput:
    """Attribute one completed event's delay and adjust exactly one knob."""
    config = payload.config
    arrival_result = parse_arrival_result(payload.outcome.arrival_result)
    observation = decompose(payload.planned, payload.actual)
    degraded = _signal_degraded(payload, observation, config)

    exclusions = evaluate_eligibility(payload, observation, arrival_result)
    if exclusions or observation is None:
        return _excluded_output(payload, exclusions, degraded)

    attribution = attribute_cause(observation, config)
    adjustment = apply_adjustment(
        observation=observation,
        attribution=attribution,
        planned=payload.planned,
        estimate=payload.current_estimate,
        arrival_result=arrival_result,
        config=config,
    )

    floor = float(config.prep_floor_minutes)
    ceiling = max(floor, adjustment.seed_minutes * config.prep_ceiling_ratio)
    reason = build_adjustment_reason(
        attribution=attribution,
        adjustment=adjustment,
        sample_count=payload.current_estimate.sample_count,
        floor=floor,
        ceiling=ceiling,
    )

    return PersonalizationOutput(
        cause=attribution.cause,
        adjusted_knob=adjustment.knob,
        previous_value=adjustment.previous_value,
        new_value=adjustment.new_value,
        adjustment_reason=reason,
        excluded_from_learning=False,
        model_version=MODEL_VERSION,
        cause_confidence=attribution.confidence,
        candidates=[
            CauseCandidate(
                cause=candidate.cause,
                confidence=candidate.confidence,
                signal_minutes=candidate.signal_minutes,
            )
            for candidate in attribution.candidates
        ],
        exclusion_reasons=[],
        degraded=_dedupe(degraded + [item.value for item in adjustment.degraded]),
    )
