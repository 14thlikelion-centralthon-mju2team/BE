"""Estimate update and guard-rails (TRD §6.2 · PRD §16.2).

    P ← (1−α)·P + α·Dactual                      α = 0.30
    arrival_result ∈ {late, rushed} → α × 1.5    failure is the stronger signal
    arrival_result = 'early'        → α × 0.7    shrinking is done carefully
    guard-rails: P ∈ [10, seed × 2] · one step ≤ 15 min
    cold start: sampleCount < 3 → keep the seed; the first clear failure gets
                one correction, capped at 20 min

The asymmetry is deliberate (TRD §6.2): one early arrival must not create the
next late one.

Only ``prep_estimate`` has a formula in the spec.  ``traffic_buffer`` reuses the
same EMA and the same step limit — TRD §6.2 names the knob without giving a
formula, and inventing a second update rule would be worse than reusing the one
already agreed.  ``notification_lead`` and ``departure_lead`` are Backend-owned
lead times that never appear in this contract, so the engine reports the knob
and leaves the values None.
"""

from dataclasses import dataclass, field

from app.contracts.common import AdjustmentKnob, DelayCause
from app.contracts.config import PersonalizationEngineConfig
from app.contracts.personalization import CurrentPrepEstimate, PlannedExecutionSnapshot
from app.domain.personalization_engine.attribution import KNOB_BY_CAUSE, Attribution
from app.domain.personalization_engine.enums import (
    FAILURE_RESULTS,
    ArrivalResult,
    PersonalizationDegraded,
)
from app.domain.personalization_engine.observation import Observation


@dataclass(frozen=True)
class Adjustment:
    """What the engine recommends changing, and what it had to clamp."""

    knob: AdjustmentKnob
    previous_value: float | None
    new_value: float | None
    #: Effective smoothing factor after the asymmetric weight.
    alpha: float | None
    #: ``seed × prep_ceiling_ratio`` input — the resolved seed in minutes.
    seed_minutes: float
    #: Per-observation step limit that was in force (max_step or cold_step).
    step_limit_minutes: float | None
    #: EMA result before the step limit and the floor/ceiling clamp.
    raw_value: float | None
    degraded: tuple[PersonalizationDegraded, ...] = field(default=())

    @property
    def changed(self) -> bool:
        return (
            self.previous_value is not None
            and self.new_value is not None
            and self.new_value != self.previous_value
        )


def resolve_seed(
    estimate: CurrentPrepEstimate,
    config: PersonalizationEngineConfig,
) -> tuple[float, tuple[PersonalizationDegraded, ...]]:
    """Seed for the ceiling guard-rail.

    ``USER_SETTING.initial_prep_minutes`` is nullable ("잘 모르겠어요"); a NULL
    seed falls back to ``SEED_FALLBACK_MIN`` and the fact is recorded (§6.2).
    """
    if estimate.seed_minutes is None:
        return float(config.seed_fallback_minutes), (PersonalizationDegraded.SEED_FALLBACK,)
    return float(estimate.seed_minutes), ()


def effective_alpha(
    arrival_result: ArrivalResult,
    config: PersonalizationEngineConfig,
) -> float:
    """Asymmetric smoothing factor, capped at 1.0 so the EMA never overshoots."""
    alpha = config.prep_ema_alpha
    if arrival_result in FAILURE_RESULTS:
        alpha *= config.late_weight
    elif arrival_result is ArrivalResult.EARLY:
        alpha *= config.early_weight
    return min(1.0, alpha)


def _apply_guardrails(
    *,
    previous: float,
    raw: float,
    step_limit: float,
    floor: float,
    ceiling: float,
) -> tuple[float, tuple[PersonalizationDegraded, ...]]:
    degraded: list[PersonalizationDegraded] = []

    stepped = max(previous - step_limit, min(previous + step_limit, raw))
    if stepped != raw:
        degraded.append(PersonalizationDegraded.STEP_LIMITED)

    clamped = stepped
    if clamped < floor:
        clamped = floor
        degraded.append(PersonalizationDegraded.FLOOR_CLAMPED)
    elif clamped > ceiling:
        clamped = ceiling
        degraded.append(PersonalizationDegraded.CEILING_CLAMPED)

    return round(clamped, 1), tuple(degraded)


def _adjust_prep_estimate(
    *,
    observation: Observation,
    attribution: Attribution,
    estimate: CurrentPrepEstimate,
    arrival_result: ArrivalResult,
    config: PersonalizationEngineConfig,
    seed_minutes: float,
    seed_degraded: tuple[PersonalizationDegraded, ...],
) -> Adjustment:
    previous = round(float(estimate.estimated_minutes), 1)
    cold_start = estimate.sample_count < config.cold_start_sample_threshold

    if cold_start:
        # The first clear failure may correct once; everything else keeps the
        # seed until three samples exist (§6.2).
        clear_failure = (
            arrival_result in FAILURE_RESULTS
            and attribution.cause is DelayCause.PREP_OVERRUN
        )
        if not clear_failure or estimate.cold_start_adjusted:
            return Adjustment(
                knob=AdjustmentKnob.NONE,
                previous_value=previous,
                new_value=previous,
                alpha=None,
                seed_minutes=seed_minutes,
                step_limit_minutes=None,
                raw_value=None,
                degraded=seed_degraded + (PersonalizationDegraded.COLD_START_HOLD,),
            )
        step_limit = float(config.cold_step_minutes)
    else:
        step_limit = float(config.max_step_minutes)

    alpha = effective_alpha(arrival_result, config)
    raw = (1.0 - alpha) * previous + alpha * observation.estimate_observed_minutes

    floor = float(config.prep_floor_minutes)
    ceiling = max(floor, seed_minutes * config.prep_ceiling_ratio)
    new_value, guard_degraded = _apply_guardrails(
        previous=previous,
        raw=raw,
        step_limit=step_limit,
        floor=floor,
        ceiling=ceiling,
    )

    knob = (
        AdjustmentKnob.PREP_ESTIMATE if new_value != previous else AdjustmentKnob.NONE
    )
    return Adjustment(
        knob=knob,
        previous_value=previous,
        new_value=new_value,
        alpha=round(alpha, 4),
        seed_minutes=seed_minutes,
        step_limit_minutes=step_limit,
        raw_value=round(raw, 2),
        degraded=seed_degraded + guard_degraded,
    )


def _adjust_traffic_buffer(
    *,
    observation: Observation,
    planned: PlannedExecutionSnapshot,
    arrival_result: ArrivalResult,
    config: PersonalizationEngineConfig,
    seed_minutes: float,
    seed_degraded: tuple[PersonalizationDegraded, ...],
) -> Adjustment:
    previous = float(planned.traffic_buffer_minutes)
    transit_error = observation.transit_error_minutes or 0.0
    alpha = effective_alpha(arrival_result, config)

    # Same EMA shape as the prep estimate: move the buffer a fraction of the
    # way towards the buffer that would have absorbed today's travel error.
    raw = (1.0 - alpha) * previous + alpha * (previous + transit_error)
    new_value, guard_degraded = _apply_guardrails(
        previous=previous,
        raw=raw,
        step_limit=float(config.max_step_minutes),
        floor=0.0,
        ceiling=float("inf"),
    )

    knob = (
        AdjustmentKnob.TRAFFIC_BUFFER if new_value != previous else AdjustmentKnob.NONE
    )
    return Adjustment(
        knob=knob,
        previous_value=previous,
        new_value=new_value,
        alpha=round(alpha, 4),
        seed_minutes=seed_minutes,
        step_limit_minutes=float(config.max_step_minutes),
        raw_value=round(raw, 2),
        degraded=seed_degraded + guard_degraded,
    )


def apply_adjustment(
    *,
    observation: Observation,
    attribution: Attribution,
    planned: PlannedExecutionSnapshot,
    estimate: CurrentPrepEstimate,
    arrival_result: ArrivalResult,
    config: PersonalizationEngineConfig,
) -> Adjustment:
    """Route the dominant cause to its knob and compute the new value."""
    seed_minutes, seed_degraded = resolve_seed(estimate, config)
    knob = KNOB_BY_CAUSE[attribution.cause]

    if knob is AdjustmentKnob.PREP_ESTIMATE:
        return _adjust_prep_estimate(
            observation=observation,
            attribution=attribution,
            estimate=estimate,
            arrival_result=arrival_result,
            config=config,
            seed_minutes=seed_minutes,
            # The seed only bounds the prep estimate, so it is only reported
            # as degraded when it actually took part in the calculation.
            seed_degraded=seed_degraded,
        )

    if knob is AdjustmentKnob.TRAFFIC_BUFFER:
        return _adjust_traffic_buffer(
            observation=observation,
            planned=planned,
            arrival_result=arrival_result,
            config=config,
            seed_minutes=seed_minutes,
            seed_degraded=(),
        )

    # notification_lead / departure_lead: the knob is named, the value lives in
    # the Backend's notification policy.  The prep estimate stays untouched —
    # that is the whole point of TR-05.
    return Adjustment(
        knob=knob,
        previous_value=None,
        new_value=None,
        alpha=None,
        seed_minutes=seed_minutes,
        step_limit_minutes=None,
        raw_value=None,
        degraded=(),
    )
