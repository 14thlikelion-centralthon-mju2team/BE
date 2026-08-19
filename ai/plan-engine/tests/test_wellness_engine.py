from app.contracts.config import WellnessEngineConfig
from app.contracts.wellness import WellnessInput, WellnessPreference
from app.domain.plan_engine.models import EnvironmentSnapshot
from app.domain.wellness import evaluate


def config() -> WellnessEngineConfig:
    return WellnessEngineConfig(weight_version="w1")


def test_missing_environment_is_degraded_without_actions() -> None:
    result = evaluate(
        WellnessInput(environment=None, estimated_outdoor_minutes=30, config=config())
    )
    assert result.wis_score is None
    assert result.actions == []
    assert result.event_armed is False
    assert result.degraded == ["env_unavailable"]


def test_wis_band_boundary_and_approved_action() -> None:
    result = evaluate(
        WellnessInput(
            environment=EnvironmentSnapshot(
                uv_index=11, pm10=150, feels_like_celsius=40, precipitation_probability=100
            ),
            estimated_outdoor_minutes=120,
            user_preferences=[
                WellnessPreference(wellness_topic="uv", is_enabled=True),
                WellnessPreference(wellness_topic="pm", is_enabled=True),
                WellnessPreference(wellness_topic="rain", is_enabled=True),
                WellnessPreference(wellness_topic="temp", is_enabled=True),
                WellnessPreference(wellness_topic="hydration", is_enabled=True),
            ],
            config=config(),
        )
    )
    assert result.wis_score == 100
    assert result.wis_band == "high"
    assert result.event_armed is True
    assert len(result.actions) == 3
    assert {action.action_code for action in result.actions} <= {
        "sunscreen",
        "mask",
        "umbrella",
        "outerwear",
        "hydration",
    }


def test_low_score_is_not_event_armed() -> None:
    result = evaluate(
        WellnessInput(
            environment=EnvironmentSnapshot(
                uv_index=0, pm10=0, feels_like_celsius=22, precipitation_probability=0
            ),
            estimated_outdoor_minutes=0,
            config=config(),
        )
    )
    assert result.wis_score == 0
    assert result.wis_band == "low"
    assert result.event_armed is False
