"""Observation decomposition (TRD §6.2).

    Δprep    = actualPrepStartedAt − prepStartAt
    Dactual  = actualDepartedAt − actualPrepStartedAt
    Δdepart  = actualDepartedAt − recommendedDepartAt
    transit  = (actualArrivedAt − actualDepartedAt) − travelMinutes

Two refinements the raw formulas need before they can drive a knob.

**Fixed prep minutes.**  The planned prep window is not the estimate alone: the
plan engine also subtracts timed routines and rain extra prep
(``prepStartAt = recommendedDepartAt − estimate − extra − routine``).  Feeding
the whole observed duration into ``P ← (1−α)P + α·Dactual`` would fold the
routine minutes into the estimate and inflate it every single day.  So the
window remainder ``fixed = (recommendedDepartAt − prepStartAt) − estimate`` is
subtracted first, and only the estimate-comparable part reaches the EMA.
TRD §6.2 does not state this; it is recorded in the README as a resolved
ambiguity.

**Lingering.**  With only three timestamps, ``Δdepart ≡ Δprep + Dactual −
window`` is an identity, so ``depart_late`` cannot be separated from
``prep_overrun``.  ``actualPrepFinishedAt`` (optional) breaks the tie.  When it
is absent the engine records ``prep_finish_unknown`` and lingering is absorbed
into the overrun signal.
"""

from dataclasses import dataclass
from datetime import datetime

from app.contracts.personalization import (
    ActualExecutionSnapshot,
    PlannedExecutionSnapshot,
)


def minutes_between(later: datetime, earlier: datetime) -> float:
    """Signed minute difference.  Both datetimes are timezone-aware."""
    return (later - earlier).total_seconds() / 60.0


@dataclass(frozen=True)
class Observation:
    """Decomposed planned-vs-actual observation for one completed event."""

    #: How much later than planned the user started preparing (signed).
    delta_prep_minutes: float
    #: Observed preparation duration — ``Dactual`` (always > 0 when eligible).
    prep_duration_minutes: float
    #: How much later than recommended the user departed (signed).
    delta_depart_minutes: float
    #: Travel error against ``travelMinutes``.  None when arrival is unknown.
    transit_error_minutes: float | None
    #: Minutes spent after prep was done *and* past the recommended departure.
    #: None when ``actualPrepFinishedAt`` was not supplied.
    lingering_minutes: float | None
    #: Planned window ``recommendedDepartAt − prepStartAt``.
    planned_window_minutes: float
    #: Routine and extra-prep minutes baked into the window by the plan engine.
    fixed_prep_minutes: float
    #: Observed duration comparable with ``estimatedPrepMinutes`` — the EMA target.
    estimate_observed_minutes: float
    #: ``estimate_observed_minutes − estimatedPrepMinutes`` (signed).
    prep_overrun_minutes: float


def decompose(
    planned: PlannedExecutionSnapshot,
    actual: ActualExecutionSnapshot,
) -> Observation | None:
    """Decompose the observation, or return None when it cannot be built.

    None means ``actual_prep_started_at`` or ``actual_departed_at`` is missing:
    without both there is no preparation duration to learn from (TRD §6.1
    완결성).
    """
    started_at = actual.actual_prep_started_at
    departed_at = actual.actual_departed_at
    if started_at is None or departed_at is None:
        return None

    planned_window = minutes_between(planned.recommended_depart_at, planned.prep_start_at)
    fixed_prep = max(0.0, planned_window - planned.estimated_prep_minutes)

    finished_at = actual.actual_prep_finished_at
    if finished_at is None:
        prep_duration = minutes_between(departed_at, started_at)
        lingering: float | None = None
    else:
        prep_duration = minutes_between(finished_at, started_at)
        # Only time spent after prep was done *and* past the recommended
        # departure is a departure delay.  Finishing early and waiting for the
        # planned departure time is exactly what the plan asked for.
        ready_at = max(finished_at, planned.recommended_depart_at)
        lingering = max(0.0, minutes_between(departed_at, ready_at))

    estimate_observed = max(0.0, prep_duration - fixed_prep)

    arrived_at = actual.actual_arrived_at
    transit_error = (
        minutes_between(arrived_at, departed_at) - planned.travel_minutes
        if arrived_at is not None
        else None
    )

    return Observation(
        delta_prep_minutes=minutes_between(started_at, planned.prep_start_at),
        prep_duration_minutes=prep_duration,
        delta_depart_minutes=minutes_between(departed_at, planned.recommended_depart_at),
        transit_error_minutes=transit_error,
        lingering_minutes=lingering,
        planned_window_minutes=planned_window,
        fixed_prep_minutes=fixed_prep,
        estimate_observed_minutes=estimate_observed,
        prep_overrun_minutes=estimate_observed - planned.estimated_prep_minutes,
    )
