"""Cause separation and knob routing (TRD §6.2 · TR-05).

TR-05: **one observation turns exactly one knob.**  Inflating the prep estimate
just because the user arrived late means a traffic jam wakes them 20 minutes
earlier the next morning, and they switch correction off entirely.  So the
delay is first split into independent signals, the dominant one becomes the
cause, and only that cause's knob moves.

Signals (minutes, only the positive part counts as delay):

===============  ==========================================================
signal           measured as
===============  ==========================================================
``prep_late``    ``max(0, Δprep)`` — started preparing later than planned
``prep_overrun`` ``max(0, estimateObserved − estimatedPrepMinutes)``
``depart_late``  ``max(0, lingering)`` — ready, past the departure time, still
                 at home.  Zero unless ``actualPrepFinishedAt`` was supplied
``traffic``      ``max(0, transitError)`` — travel took longer than the route
===============  ==========================================================

Confidence is each signal's share of the summed delay, which keeps it in
``[0, 1]``, monotone in its own signal, and directly storable in
``EVENT_DELAY_REASON.confidence``.  Signals below
``attribution_min_signal_minutes`` are noise and are dropped before the share
is computed.  Ties resolve along the causal chain — an earlier cause
mechanically produces the later ones — which also keeps the estimate knob, the
one TR-05 warns about, last.
"""

from dataclasses import dataclass

from app.contracts.common import AdjustmentKnob, DelayCause
from app.contracts.config import PersonalizationEngineConfig
from app.domain.personalization_engine.observation import Observation

#: Each cause adjusts its own knob and nothing else (TRD §6.2 손잡이 라우팅).
KNOB_BY_CAUSE: dict[DelayCause, AdjustmentKnob] = {
    DelayCause.PREP_LATE: AdjustmentKnob.NOTIFICATION_LEAD,
    DelayCause.PREP_OVERRUN: AdjustmentKnob.PREP_ESTIMATE,
    DelayCause.DEPART_LATE: AdjustmentKnob.DEPARTURE_LEAD,
    DelayCause.TRAFFIC: AdjustmentKnob.TRAFFIC_BUFFER,
    DelayCause.EXTERNAL: AdjustmentKnob.NONE,
    # No delay to attribute: the observation is still a clean measurement of
    # how long preparing actually takes, so it refines the estimate.
    DelayCause.UNKNOWN: AdjustmentKnob.PREP_ESTIMATE,
}

#: Tie-break order: earliest link in the causal chain first.
CAUSAL_ORDER: tuple[DelayCause, ...] = (
    DelayCause.PREP_LATE,
    DelayCause.PREP_OVERRUN,
    DelayCause.DEPART_LATE,
    DelayCause.TRAFFIC,
)


@dataclass(frozen=True)
class Candidate:
    cause: DelayCause
    confidence: float
    signal_minutes: float


@dataclass(frozen=True)
class Attribution:
    cause: DelayCause
    #: None when there was no delay to attribute.
    confidence: float | None
    candidates: tuple[Candidate, ...]

    @property
    def has_delay(self) -> bool:
        return bool(self.candidates)


def _signals(observation: Observation) -> dict[DelayCause, float]:
    return {
        DelayCause.PREP_LATE: max(0.0, observation.delta_prep_minutes),
        DelayCause.PREP_OVERRUN: max(0.0, observation.prep_overrun_minutes),
        DelayCause.DEPART_LATE: max(0.0, observation.lingering_minutes or 0.0),
        DelayCause.TRAFFIC: max(0.0, observation.transit_error_minutes or 0.0),
    }


def attribute_cause(
    observation: Observation,
    config: PersonalizationEngineConfig,
) -> Attribution:
    """Pick the dominant cause, or UNKNOWN when no signal clears the floor."""
    floor = float(config.attribution_min_signal_minutes)
    signals = {
        cause: minutes
        for cause, minutes in _signals(observation).items()
        if minutes >= floor and minutes > 0.0
    }
    total = sum(signals.values())
    if total <= 0.0:
        return Attribution(cause=DelayCause.UNKNOWN, confidence=None, candidates=())

    candidates = tuple(
        Candidate(
            cause=cause,
            confidence=round(signals[cause] / total, 3),
            signal_minutes=round(signals[cause], 1),
        )
        for cause in CAUSAL_ORDER
        if cause in signals
    )
    # max() keeps the first maximum, and candidates are already in causal
    # order, so the tie-break is deterministic.
    dominant = max(candidates, key=lambda candidate: candidate.confidence)
    return Attribution(
        cause=dominant.cause,
        confidence=dominant.confidence,
        candidates=candidates,
    )
