"""Learning sample qualification (TRD §6.1 · MODEL-01, §6.4).

An unqualified sample is not an error: the engine still answers 200 with
``excludedFromLearning=true`` and the reason codes, so the Backend can record
the exclusion rate as a guard-rail metric (§16).
"""

from app.contracts.config import PersonalizationEngineConfig
from app.contracts.personalization import PersonalizationInput
from app.domain.personalization_engine.enums import (
    GEO_RESULT_SOURCE,
    ArrivalResult,
    ExclusionReason,
)
from app.domain.personalization_engine.observation import Observation


def evaluate_eligibility(
    payload: PersonalizationInput,
    observation: Observation | None,
    arrival_result: ArrivalResult,
) -> list[ExclusionReason]:
    """Return every failed filter, in a stable order.  Empty means eligible."""
    config: PersonalizationEngineConfig = payload.config
    reasons: list[ExclusionReason] = []

    # 일정 유효 — a rescheduled or deleted event invalidates the planned
    # baseline, so the comparison is meaningless.  Checked first because it is
    # the one exclusion that maps to DelayCause.EXTERNAL.
    if payload.outcome.event_modified_after_plan:
        reasons.append(ExclusionReason.EVENT_MODIFIED)

    # 되돌리기 — value rollback alone would let the same correction reappear on
    # the next tick, so the sample is excluded permanently (§6.4).
    if payload.outcome.learning_reverted:
        reasons.append(ExclusionReason.LEARNING_REVERTED)

    # 건너뜀 제외
    if arrival_result is ArrivalResult.UNKNOWN:
        reasons.append(ExclusionReason.ARRIVAL_RESULT_UNKNOWN)
    if payload.outcome.auto_manage_excluded:
        reasons.append(ExclusionReason.AUTO_MANAGE_EXCLUDED)

    # 완결성
    if observation is None:
        reasons.append(ExclusionReason.INCOMPLETE_TIMESTAMPS)

    # 시계 정합 (TR-02) — a skewed device clock makes every minute suspect.
    skew_seconds = payload.actual.clock_skew_seconds
    if skew_seconds is not None and abs(skew_seconds) > config.clock_skew_tolerance_seconds:
        reasons.append(ExclusionReason.CLOCK_SKEW)

    # 이상치 절단 — "pressed 준비 시작 and forgot".
    if observation is not None and not (
        0.0 < observation.prep_duration_minutes <= config.prep_outlier_max_minutes
    ):
        reasons.append(ExclusionReason.PREP_DURATION_OUTLIER)

    # 출처 신뢰 — a geofence guess below the bar must not teach the model.
    if (payload.actual.result_source or "").strip().lower() == GEO_RESULT_SOURCE:
        confidence = payload.actual.result_confidence
        if confidence is None or confidence < config.geo_min_confidence:
            reasons.append(ExclusionReason.GEO_CONFIDENCE_LOW)

    return reasons
