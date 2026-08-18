"""Tests for shared contract base and enum behavior."""

from datetime import datetime, timezone

import pytest
from pydantic import ValidationError

from app.contracts.base import ContractModel
from app.contracts.common import (
    AdjustmentKnob,
    AnchorMode,
    DelayCause,
    EngineType,
    PrepActionType,
    PrepSourceType,
    WellnessBand,
    WellnessTopic,
)

# ──────────────────────────────────────────────────────────────────────────────
# camelCase serialization
# ──────────────────────────────────────────────────────────────────────────────


class _SampleModel(ContractModel):
    first_name: str
    last_value: int


def test_camel_case_serialization():
    m = _SampleModel(first_name="hello", last_value=42)
    d = m.model_dump(by_alias=True)
    assert "firstName" in d
    assert "lastValue" in d
    assert "first_name" not in d


def test_camel_case_json_output():
    m = _SampleModel(first_name="hello", last_value=42)
    json_str = m.model_dump_json(by_alias=True)
    assert '"firstName"' in json_str
    assert '"lastValue"' in json_str


def test_snake_case_input_accepted():
    m = _SampleModel.model_validate({"first_name": "x", "last_value": 1})
    assert m.first_name == "x"


def test_camel_case_input_accepted():
    m = _SampleModel.model_validate({"firstName": "x", "lastValue": 1})
    assert m.first_name == "x"


# ──────────────────────────────────────────────────────────────────────────────
# extra=forbid
# ──────────────────────────────────────────────────────────────────────────────


def test_extra_field_rejected():
    with pytest.raises(ValidationError) as exc_info:
        _SampleModel.model_validate({"firstName": "x", "lastValue": 1, "unknown": True})
    assert "extra" in str(exc_info.value).lower() or "Extra inputs" in str(exc_info.value)


# ──────────────────────────────────────────────────────────────────────────────
# timezone-aware datetime enforcement
# ──────────────────────────────────────────────────────────────────────────────


class _TimeModel(ContractModel):
    created_at: datetime


def test_aware_datetime_accepted():
    m = _TimeModel.model_validate({"createdAt": "2026-08-18T14:00:00+09:00"})
    assert m.created_at.utcoffset() is not None


def test_utc_z_datetime_accepted():
    m = _TimeModel.model_validate({"createdAt": "2026-08-18T05:00:00Z"})
    assert m.created_at.tzinfo is not None


def test_naive_datetime_rejected():
    with pytest.raises(ValidationError):
        _TimeModel.model_validate({"createdAt": "2026-08-18T14:00:00"})


def test_offset_preserved_in_output():
    from datetime import timedelta

    kst = timezone(timedelta(hours=9))
    m = _TimeModel(created_at=datetime(2026, 8, 18, 14, 0, tzinfo=kst))
    dumped = m.model_dump(by_alias=True)
    assert dumped["createdAt"].utcoffset() == timedelta(hours=9)


# ──────────────────────────────────────────────────────────────────────────────
# Enum string serialization
# ──────────────────────────────────────────────────────────────────────────────


def test_enum_values_are_strings():
    assert AnchorMode.ARRIVE_BY == "arrive_by"
    assert PrepActionType.TIMED_ROUTINE == "timed_routine"
    assert PrepSourceType.WEATHER == "weather"
    assert DelayCause.PREP_LATE == "prep_late"
    assert AdjustmentKnob.PREP_ESTIMATE == "prep_estimate"
    assert WellnessTopic.UV == "uv"
    assert WellnessBand.HIGH == "high"
    assert EngineType.PLAN == "plan"


def test_invalid_enum_rejected():
    from app.contracts.personalization import PersonalizationOutput

    with pytest.raises(ValidationError):
        PersonalizationOutput.model_validate(
            {
                "cause": "invalid_cause",
                "adjustedKnob": "none",
                "excludedFromLearning": True,
                "modelVersion": "v1",
                "contractVersion": "m0-v1",
            }
        )


# ──────────────────────────────────────────────────────────────────────────────
# Mutable default safety
# ──────────────────────────────────────────────────────────────────────────────


def test_list_fields_are_independent():
    """Two instances must not share a mutable list."""
    from app.contracts.wellness import WellnessOutput

    a = WellnessOutput(
        event_armed=False,
        weight_version="v1",
        contract_version="m0-v1",
    )
    b = WellnessOutput(
        event_armed=False,
        weight_version="v1",
        contract_version="m0-v1",
    )
    a.actions.append(None)  # type: ignore[arg-type]
    assert len(b.actions) == 0


# ──────────────────────────────────────────────────────────────────────────────
# Nullable field handling
# ──────────────────────────────────────────────────────────────────────────────


def test_nullable_fields_accept_none():
    from app.contracts.personalization import PersonalizationOutput

    out = PersonalizationOutput.model_validate(
        {
            "cause": "unknown",
            "adjustedKnob": "none",
            "previousValue": None,
            "newValue": None,
            "adjustmentReason": None,
            "excludedFromLearning": True,
            "modelVersion": "stub",
            "contractVersion": "m0-v1",
        }
    )
    assert out.previous_value is None
    assert out.new_value is None


def test_contract_version_serialized():
    from app.contracts.personalization import PersonalizationOutput

    out = PersonalizationOutput(
        cause=DelayCause.UNKNOWN,
        adjusted_knob=AdjustmentKnob.NONE,
        excluded_from_learning=True,
        model_version="stub",
        contract_version="m0-v1",
    )
    d = out.model_dump(by_alias=True)
    assert d["contractVersion"] == "m0-v1"
