"""Tests for Wellness Engine contract models."""

import pytest
from pydantic import ValidationError

from app.contracts.common import WellnessBand, WellnessTopic
from app.contracts.config import WellnessEngineConfig
from app.contracts.wellness import (
    MAX_WELLNESS_ACTIONS,
    NormalizedWellnessLoads,
    WellnessAction,
    WellnessInput,
    WellnessOutput,
)


def _valid_config() -> dict:
    return {"weightVersion": "w1"}


def _valid_environment() -> dict:
    return {
        "precipitationProbability": 30,
        "feelsLikeCelsius": 28.5,
        "observedAt": "2026-08-18T12:00:00+09:00",
    }


def _valid_preference() -> dict:
    return {
        "wellnessTopic": "uv",
        "isEnabled": True,
        "remindIntervalMinutes": 120,
        "dailyEventCap": 1,
    }


def _valid_input() -> dict:
    return {
        "environment": _valid_environment(),
        "estimatedOutdoorMinutes": 45,
        "userPreferences": [_valid_preference()],
        "existingPrepItems": [],
        "config": _valid_config(),
    }


# ──────────────────────────────────────────────────────────────────────────────
# Positive cases
# ──────────────────────────────────────────────────────────────────────────────


class TestWellnessInputValid:
    def test_full_input(self):
        inp = WellnessInput.model_validate(_valid_input())
        assert inp.estimated_outdoor_minutes == 45
        assert len(inp.user_preferences) == 1
        assert inp.user_preferences[0].wellness_topic == WellnessTopic.UV

    def test_null_environment_allowed(self):
        data = _valid_input()
        data["environment"] = None
        inp = WellnessInput.model_validate(data)
        assert inp.environment is None

    def test_empty_preferences_allowed(self):
        data = _valid_input()
        data["userPreferences"] = []
        inp = WellnessInput.model_validate(data)
        assert len(inp.user_preferences) == 0

    def test_existing_prep_items(self):
        data = _valid_input()
        data["existingPrepItems"] = [
            {
                "itemId": "pi-1",
                "itemName": "선크림",
                "actionType": "carry",
                "sourceType": "rule",
                "appliedMinutes": 0,
                "isSensitive": False,
            }
        ]
        inp = WellnessInput.model_validate(data)
        assert inp.existing_prep_items[0].item_name == "선크림"


class TestWellnessOutputValid:
    def test_full_output(self):
        out = WellnessOutput.model_validate(
            {
                "wisScore": 65,
                "wisBand": "mid",
                "normalizedLoads": {
                    "uvLoad": 0.8,
                    "pmLoad": 0.2,
                    "thermalLoad": 0.5,
                    "outdoorLoad": 0.6,
                    "interestMultiplier": 1.15,
                },
                "actions": [
                    {
                        "wellnessTopic": "uv",
                        "actionCode": "sunscreen_reapply",
                        "actionLabel": "선크림 재도포",
                        "displayRank": 1,
                        "reason": "UV 지수 8 이상, 야외 45분 예상",
                    }
                ],
                "eventArmed": True,
                "weightVersion": "w1",
                "contractVersion": "m0-v1",
                "degraded": [],
            }
        )
        assert out.wis_score == 65
        assert out.wis_band == WellnessBand.MID
        assert len(out.actions) == 1
        assert out.event_armed is True

    def test_null_scores_when_degraded(self):
        out = WellnessOutput.model_validate(
            {
                "wisScore": None,
                "wisBand": None,
                "normalizedLoads": None,
                "actions": [],
                "eventArmed": False,
                "weightVersion": "w1",
                "contractVersion": "m0-v1",
                "degraded": ["env_unavailable"],
            }
        )
        assert out.wis_score is None
        assert out.normalized_loads is None


# ──────────────────────────────────────────────────────────────────────────────
# Actions constraint: max 3
# ──────────────────────────────────────────────────────────────────────────────


class TestWellnessActionsLimit:
    def test_exactly_three_allowed(self):
        actions = [
            {
                "wellnessTopic": "uv",
                "actionCode": f"action_{i}",
                "actionLabel": f"Action {i}",
                "displayRank": i,
                "reason": "test",
            }
            for i in range(1, 4)
        ]
        out = WellnessOutput.model_validate(
            {
                "actions": actions,
                "eventArmed": False,
                "weightVersion": "w1",
                "contractVersion": "m0-v1",
            }
        )
        assert len(out.actions) == 3

    def test_four_actions_rejected(self):
        actions = [
            {
                "wellnessTopic": "uv",
                "actionCode": f"action_{i}",
                "actionLabel": f"Action {i}",
                "displayRank": min(i, 3),
                "reason": "test",
            }
            for i in range(1, 5)
        ]
        with pytest.raises(ValidationError, match="at most 3"):
            WellnessOutput.model_validate(
                {
                    "actions": actions,
                    "eventArmed": False,
                    "weightVersion": "w1",
                    "contractVersion": "m0-v1",
                }
            )


# ──────────────────────────────────────────────────────────────────────────────
# Range validations
# ──────────────────────────────────────────────────────────────────────────────


class TestWellnessRanges:
    def test_wis_score_over_100_rejected(self):
        with pytest.raises(ValidationError):
            WellnessOutput.model_validate(
                {
                    "wisScore": 101,
                    "eventArmed": False,
                    "weightVersion": "w1",
                    "contractVersion": "m0-v1",
                }
            )

    def test_wis_score_negative_rejected(self):
        with pytest.raises(ValidationError):
            WellnessOutput.model_validate(
                {
                    "wisScore": -1,
                    "eventArmed": False,
                    "weightVersion": "w1",
                    "contractVersion": "m0-v1",
                }
            )

    def test_display_rank_zero_rejected(self):
        with pytest.raises(ValidationError):
            WellnessAction.model_validate(
                {
                    "wellnessTopic": "uv",
                    "actionCode": "test",
                    "actionLabel": "test",
                    "displayRank": 0,  # ge=1
                    "reason": "test",
                }
            )

    def test_display_rank_over_max_rejected(self):
        with pytest.raises(ValidationError):
            WellnessAction.model_validate(
                {
                    "wellnessTopic": "uv",
                    "actionCode": "test",
                    "actionLabel": "test",
                    "displayRank": MAX_WELLNESS_ACTIONS + 1,
                    "reason": "test",
                }
            )

    def test_normalized_load_over_one_rejected(self):
        with pytest.raises(ValidationError):
            NormalizedWellnessLoads.model_validate(
                {
                    "uvLoad": 1.5,  # le=1.0
                    "pmLoad": 0.0,
                    "thermalLoad": 0.0,
                    "outdoorLoad": 0.0,
                    "interestMultiplier": 1.0,
                }
            )


# ──────────────────────────────────────────────────────────────────────────────
# Config validation
# ──────────────────────────────────────────────────────────────────────────────


class TestWellnessConfig:
    def test_default_values(self):
        cfg = WellnessEngineConfig.model_validate({"weightVersion": "w1"})
        assert cfg.wis_weight_uv == 0.35
        assert cfg.wis_band_card == 40
        assert cfg.outdoor_cap_minutes == 120

    def test_weight_over_one_rejected(self):
        with pytest.raises(ValidationError):
            WellnessEngineConfig.model_validate(
                {
                    "weightVersion": "w1",
                    "wisWeightUv": 1.5,
                }
            )

    def test_band_threshold_over_100_rejected(self):
        with pytest.raises(ValidationError):
            WellnessEngineConfig.model_validate(
                {
                    "weightVersion": "w1",
                    "wisBandEvent": 101,
                }
            )
