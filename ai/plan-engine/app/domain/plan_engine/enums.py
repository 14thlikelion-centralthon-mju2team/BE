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


class PredictionConfidence(StrEnum):
    HIGH = "high"
    MID = "mid"
    LOW = "low"


class DegradedReason(StrEnum):
    ROUTE_STALE = "route_stale"
    ENV_UNAVAILABLE = "env_unavailable"
    PREP_ESTIMATE_MISSING = "prep_estimate_missing"
    CONFIG_FALLBACK = "config_fallback"
