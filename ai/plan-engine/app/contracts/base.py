"""Shared base model and utilities for all engine contracts.

Re-exports the canonical ``CamelModel`` from the domain layer so every contract
module has a single import path while the M1 plan engine continues to own the
original definition.
"""

from datetime import datetime

from pydantic import field_validator

from app.domain.plan_engine.models import CamelModel, to_camel

__all__ = [
    "CamelModel",
    "ContractModel",
    "require_aware_datetime",
    "to_camel",
]


def require_aware_datetime(value: datetime | None) -> datetime | None:
    """Reject naive datetimes.  Accepts None passthrough for nullable fields."""
    if value is not None and (value.tzinfo is None or value.utcoffset() is None):
        raise ValueError("timezone-aware datetime is required")
    return value


class ContractModel(CamelModel):
    """Extended base that validates all datetime fields are timezone-aware.

    Subclasses inherit camelCase aliasing, extra=forbid, and automatic
    timezone enforcement on every ``datetime`` field.
    """

    @field_validator("*", mode="after")
    @classmethod
    def _enforce_aware_datetimes(cls, value: object) -> object:
        if isinstance(value, datetime):
            if value.tzinfo is None or value.utcoffset() is None:
                raise ValueError("timezone-aware datetime is required")
        return value
