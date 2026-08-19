from enum import StrEnum


class AnchorMode(StrEnum):
    ARRIVE_BY = "arrive_by"
    DEPART_AT = "depart_at"


class PrepActionType(StrEnum):
    TIMED_ROUTINE = "timed_routine"
    CARRY = "carry"
    CONSUME = "consume"
    PURCHASE = "purchase"


class PrepSourceType(StrEnum):
    RULE = "rule"
    EVENT_ITEM = "event_item"
    WEATHER = "weather"


class AirQualityGrade(StrEnum):
    """에어코리아 통합 등급 (ERD ``PLAN_CONTEXT``, TRD §7.2 P 정규화).

    The wellness engine normalises the *grade*, not the raw µg/m³ value, so the
    Backend maps the provider's grade code to one of these four before sending.
    Deriving a grade from raw concentrations would mean this engine deciding
    air-quality standards, which is not its job.
    """

    GOOD = "good"
    MODERATE = "moderate"
    BAD = "bad"
    VERY_BAD = "very_bad"


class PredictionConfidence(StrEnum):
    HIGH = "high"
    MID = "mid"
    LOW = "low"


class DegradedReason(StrEnum):
    ROUTE_STALE = "route_stale"
    ENV_UNAVAILABLE = "env_unavailable"
    PREP_ESTIMATE_MISSING = "prep_estimate_missing"
    CONFIG_FALLBACK = "config_fallback"
