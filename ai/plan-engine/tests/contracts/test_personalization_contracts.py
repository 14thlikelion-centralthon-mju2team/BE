"""Tests for Personalization Engine contract models."""

from datetime import datetime, timedelta, timezone

import pytest
from pydantic import ValidationError

from app.contracts.common import AdjustmentKnob, DelayCause
from app.contracts.config import PersonalizationEngineConfig
from app.contracts.personalization import (
    PersonalizationInput,
    PersonalizationOutput,
)

KST = timezone(timedelta(hours=9))
NOW = datetime(2026, 8, 18, 14, 0, tzinfo=KST)


def _valid_config() -> dict:
    return {"modelVersion": "v1"}


def _valid_planned() -> dict:
    return {
        "prepStartAt": "2026-08-18T12:00:00+09:00",
        "recommendedDepartAt": "2026-08-18T12:40:00+09:00",
        "targetArriveAt": "2026-08-18T13:30:00+09:00",
        "estimatedPrepMinutes": 30,
        "travelMinutes": 40,
        "trafficBufferMinutes": 5,
    }


def _valid_actual() -> dict:
    return {
        "actualPrepStartedAt": "2026-08-18T12:05:00+09:00",
        "actualDepartedAt": "2026-08-18T12:50:00+09:00",
        "actualArrivedAt": "2026-08-18T13:35:00+09:00",
        "resultSource": "geofence",
    }


def _valid_outcome() -> dict:
    return {"arrivalResult": "late", "rushAssessment": "rushed"}


def _valid_estimate() -> dict:
    return {"estimatedMinutes": 30.0, "sampleCount": 5, "confidence": 0.8, "modelVersion": "v1"}


def _valid_input() -> dict:
    return {
        "eventId": "evt-001",
        "planned": _valid_planned(),
        "actual": _valid_actual(),
        "outcome": _valid_outcome(),
        "currentEstimate": _valid_estimate(),
        "config": _valid_config(),
    }


# ──────────────────────────────────────────────────────────────────────────────
# Positive cases
# ──────────────────────────────────────────────────────────────────────────────


class TestPersonalizationInputValid:
    def test_full_input(self):
        inp = PersonalizationInput.model_validate(_valid_input())
        assert inp.event_id == "evt-001"
        assert inp.planned.estimated_prep_minutes == 30

    def test_actual_all_null(self):
        data = _valid_input()
        data["actual"] = {}
        inp = PersonalizationInput.model_validate(data)
        assert inp.actual.actual_prep_started_at is None
        assert inp.actual.actual_departed_at is None

    def test_output_all_nullable_none(self):
        out = PersonalizationOutput.model_validate(
            {
                "cause": "unknown",
                "adjustedKnob": "none",
                "previousValue": None,
                "newValue": None,
                "adjustmentReason": None,
                "excludedFromLearning": False,
                "modelVersion": "v1",
                "contractVersion": "m0-v1",
            }
        )
        assert out.excluded_from_learning is False


class TestPersonalizationOutputValid:
    def test_full_output(self):
        out = PersonalizationOutput.model_validate(
            {
                "cause": "prep_late",
                "adjustedKnob": "prep_estimate",
                "previousValue": 30.0,
                "newValue": 35.0,
                "adjustmentReason": "EMA adjustment",
                "excludedFromLearning": False,
                "modelVersion": "v1",
                "contractVersion": "m0-v1",
            }
        )
        assert out.cause == DelayCause.PREP_LATE
        assert out.adjusted_knob == AdjustmentKnob.PREP_ESTIMATE
        assert out.new_value == 35.0

    def test_model_version_required(self):
        with pytest.raises(ValidationError):
            PersonalizationOutput.model_validate(
                {
                    "cause": "unknown",
                    "adjustedKnob": "none",
                    "excludedFromLearning": True,
                    "modelVersion": "",  # min_length=1
                    "contractVersion": "m0-v1",
                }
            )


# ──────────────────────────────────────────────────────────────────────────────
# Negative cases
# ──────────────────────────────────────────────────────────────────────────────


class TestPersonalizationInputInvalid:
    def test_invalid_cause_enum(self):
        with pytest.raises(ValidationError):
            PersonalizationOutput.model_validate(
                {
                    "cause": "not_a_cause",
                    "adjustedKnob": "none",
                    "excludedFromLearning": True,
                    "modelVersion": "v1",
                    "contractVersion": "m0-v1",
                }
            )

    def test_invalid_knob_enum(self):
        with pytest.raises(ValidationError):
            PersonalizationOutput.model_validate(
                {
                    "cause": "unknown",
                    "adjustedKnob": "invalid_knob",
                    "excludedFromLearning": True,
                    "modelVersion": "v1",
                    "contractVersion": "m0-v1",
                }
            )

    def test_naive_datetime_in_planned(self):
        data = _valid_input()
        data["planned"]["prepStartAt"] = "2026-08-18T12:00:00"  # no offset
        with pytest.raises(ValidationError):
            PersonalizationInput.model_validate(data)

    def test_naive_datetime_in_actual(self):
        data = _valid_input()
        data["actual"]["actualDepartedAt"] = "2026-08-18T12:50:00"  # no offset
        with pytest.raises(ValidationError):
            PersonalizationInput.model_validate(data)

    def test_negative_prep_minutes(self):
        data = _valid_input()
        data["planned"]["estimatedPrepMinutes"] = -5
        with pytest.raises(ValidationError):
            PersonalizationInput.model_validate(data)

    def test_confidence_out_of_range(self):
        data = _valid_input()
        data["currentEstimate"]["confidence"] = 1.5
        with pytest.raises(ValidationError):
            PersonalizationInput.model_validate(data)


# ──────────────────────────────────────────────────────────────────────────────
# Config validation
# ──────────────────────────────────────────────────────────────────────────────


class TestPersonalizationConfig:
    def test_default_values(self):
        cfg = PersonalizationEngineConfig.model_validate({"modelVersion": "v1"})
        assert cfg.prep_ema_alpha == 0.30
        assert cfg.late_weight == 1.50
        assert cfg.max_step_minutes == 15

    def test_alpha_out_of_range(self):
        with pytest.raises(ValidationError):
            PersonalizationEngineConfig.model_validate(
                {
                    "modelVersion": "v1",
                    "prepEmaAlpha": 0.0,  # gt=0 required
                }
            )

    def test_alpha_over_one(self):
        with pytest.raises(ValidationError):
            PersonalizationEngineConfig.model_validate(
                {
                    "modelVersion": "v1",
                    "prepEmaAlpha": 1.5,  # le=1.0 required
                }
            )
