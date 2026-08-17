from app.domain.plan_engine.enums import DegradedReason, PredictionConfidence
from app.domain.plan_engine.models import ENGINE_USED_CONFIG_FIELDS, PlanInput


def degraded_reasons(plan_input: PlanInput) -> list[DegradedReason]:
    degraded: list[DegradedReason] = []
    if plan_input.prep_estimate is None:
        degraded.append(DegradedReason.PREP_ESTIMATE_MISSING)
    if plan_input.selected_route.is_stale:
        degraded.append(DegradedReason.ROUTE_STALE)
    if plan_input.environment is None:
        degraded.append(DegradedReason.ENV_UNAVAILABLE)

    # Only config the calculation actually reads can degrade the result.
    omitted = ENGINE_USED_CONFIG_FIELDS - plan_input.config.model_fields_set
    if omitted:
        degraded.append(DegradedReason.CONFIG_FALLBACK)
    return degraded


def prediction_confidence(degraded: list[DegradedReason]) -> PredictionConfidence:
    """Rule-based input completeness (prompt §15).

    Decision on record: a missing prep estimate or a stale route is treated as
    LOW on its own, because both directly distort the returned timestamps.
    Prompt §15 lists a missing prep estimate under both MID and LOW; this
    module resolves that ambiguity in favour of LOW and is the single place to
    change if the team decides otherwise.
    """
    if any(
        reason in degraded
        for reason in (DegradedReason.PREP_ESTIMATE_MISSING, DegradedReason.ROUTE_STALE)
    ):
        return PredictionConfidence.LOW
    if degraded:
        return PredictionConfidence.MID
    return PredictionConfidence.HIGH
