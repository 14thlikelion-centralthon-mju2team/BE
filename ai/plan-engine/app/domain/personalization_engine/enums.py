"""Enums local to the personalization engine.

``DelayCause`` and ``AdjustmentKnob`` are part of the frozen contract and live
in ``app.contracts.common``; they are not redefined here.

The string values below are new in M2.  They travel in
``PersonalizationOutput.exclusionReasons`` / ``.degraded``, which the contract
types as ``list[str]`` — adding codes there is non-breaking (contract doc §10).
"""

from enum import StrEnum


class ArrivalResult(StrEnum):
    """``EVENT_EXECUTION.arrival_result`` (ERD v3).

    The contract keeps the field a free string so an unknown Backend value can
    never 422 a completed event.  ``parse_arrival_result`` maps anything
    unrecognised to ``UNKNOWN``, which drops the sample from learning
    (TRD §6.1 건너뜀 제외).
    """

    EARLY = "early"
    ON_TIME = "on_time"
    RUSHED = "rushed"
    LATE = "late"
    UNKNOWN = "unknown"


#: Outcomes that mean the plan failed.  Failure is the stronger signal, so the
#: EMA uses ``late_weight`` for these (TRD §6.2).
FAILURE_RESULTS = frozenset({ArrivalResult.LATE, ArrivalResult.RUSHED})

#: ``result_source`` value that needs the geofence confidence gate (§6.1).
GEO_RESULT_SOURCE = "geo"


def parse_arrival_result(value: str | None) -> ArrivalResult:
    """Map a Backend string to ``ArrivalResult``, defaulting to ``UNKNOWN``."""
    if value is None:
        return ArrivalResult.UNKNOWN
    try:
        return ArrivalResult(value.strip().lower())
    except ValueError:
        return ArrivalResult.UNKNOWN


class ExclusionReason(StrEnum):
    """Why a sample failed the learning-sample qualification (TRD §6.1, §6.4)."""

    #: ``actual_prep_started_at`` or ``actual_departed_at`` missing.
    INCOMPLETE_TIMESTAMPS = "incomplete_timestamps"
    #: Device clock disagreed with the server beyond the tolerance (TR-02).
    CLOCK_SKEW = "clock_skew"
    #: ``arrival_result = 'unknown'`` — the user skipped the check-in.
    ARRIVAL_RESULT_UNKNOWN = "arrival_result_unknown"
    #: The event opted out of automatic management.
    AUTO_MANAGE_EXCLUDED = "auto_manage_excluded"
    #: Observed prep duration outside ``(0, prep_outlier_max_minutes]``.
    PREP_DURATION_OUTLIER = "prep_duration_outlier"
    #: ``result_source='geo'`` below the geofence confidence bar (§9.2).
    GEO_CONFIDENCE_LOW = "geo_confidence_low"
    #: Event deleted or rescheduled after the plan — planned baseline invalid.
    EVENT_MODIFIED = "event_modified"
    #: The user reverted this correction; never learn from it again (§6.4).
    LEARNING_REVERTED = "learning_reverted"


class PersonalizationDegraded(StrEnum):
    """What was missing and what the engine assumed instead."""

    #: ``seedMinutes`` absent → ``config.seedFallbackMinutes`` used (§6.2).
    SEED_FALLBACK = "seed_fallback"
    #: No ``actualPrepFinishedAt`` → lingering after prep cannot be told apart
    #: from prep running long, so it is absorbed into ``prep_overrun``.
    PREP_FINISH_UNKNOWN = "prep_finish_unknown"
    #: No ``actualArrivedAt`` → the traffic signal could not be measured.
    TRANSIT_UNKNOWN = "transit_unknown"
    #: ``sampleCount`` below the cold-start threshold → seed kept.
    COLD_START_HOLD = "cold_start_hold"
    #: The EMA result was capped by the per-observation step limit.
    STEP_LIMITED = "step_limited"
    #: The result was raised to ``prep_floor_minutes``.
    FLOOR_CLAMPED = "floor_clamped"
    #: The result was lowered to ``seed × prep_ceiling_ratio``.
    CEILING_CLAMPED = "ceiling_clamped"
    #: A config key the calculation reads was omitted, so its default was used.
    CONFIG_FALLBACK = "config_fallback"
