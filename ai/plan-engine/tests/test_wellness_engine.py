"""Unit tests for the individual rules of the wellness engine (TRD §7).

The golden fixtures pin whole responses; these pin one rule each so a failure
says which rule broke.
"""

from typing import Any

import pytest
from pydantic import ValidationError

from app.contracts.common import WellnessBand, WellnessTopic
from app.contracts.config import WellnessEngineConfig
from app.contracts.wellness import (
    DailyEventSummary,
    PrepItemSnapshot,
    WellnessInput,
    WellnessPreference,
)
from app.domain.plan_engine.enums import AirQualityGrade
from app.domain.plan_engine.models import EnvironmentSnapshot
from app.domain.wellness_engine.dwl import summarize_daily_load
from app.domain.wellness_engine.engine import evaluate_wellness
from app.domain.wellness_engine.enums import (
    ArmingGate,
    PmBucket,
    RainBucket,
    TempBucket,
    UvBucket,
    WellnessActionCode,
    WellnessDegraded,
)
from app.domain.wellness_engine.normalize import (
    interest_multiplier,
    outdoor_load,
    pm_load,
    thermal_load,
    uv_load,
)
from app.domain.wellness_engine.quantize import quantize
from app.domain.wellness_engine.rls import compute_rush_load_score
from app.domain.wellness_engine.wis import wis_band

CONFIG = WellnessEngineConfig(weight_version="unit-w1")
FULL_CONFIG: dict[str, Any] = CONFIG.model_dump(by_alias=True)


def env(**overrides: Any) -> EnvironmentSnapshot:
    values: dict[str, Any] = {
        "uvIndex": None,
        "airGrade": None,
        "feelsLikeCelsius": None,
        "precipitationProbability": None,
    }
    values.update(overrides)
    return EnvironmentSnapshot.model_validate(values)


def build(**overrides: Any) -> WellnessInput:
    payload: dict[str, Any] = {
        "environment": {
            "uvIndex": 5.0,
            "airGrade": "good",
            "feelsLikeCelsius": 20.0,
            "precipitationProbability": 0,
        },
        "estimatedOutdoorMinutes": 60,
        "userPreferences": [],
        "existingPrepItems": [],
        "eventState": {},
        "config": FULL_CONFIG,
    }
    payload.update(overrides)
    return WellnessInput.model_validate(payload)


# ──────────────────────────────────────────────────────────────────────────────
# 정규화 (§7.2)
# ──────────────────────────────────────────────────────────────────────────────


class TestNormalization:
    @pytest.mark.parametrize(
        ("index", "expected"),
        [(0.0, 0.0), (6.0, 0.6), (8.0, 0.8), (11.0, 1.0), (20.0, 1.0)],
    )
    def test_uv_anchors_match_the_spec(self, index: float, expected: float) -> None:
        load, degraded = uv_load(index, CONFIG)
        assert load == pytest.approx(expected)
        assert degraded == ()

    def test_missing_uv_is_zero_and_degraded(self) -> None:
        load, degraded = uv_load(None, CONFIG)
        assert load == 0.0
        assert degraded == (WellnessDegraded.UV_UNAVAILABLE,)

    @pytest.mark.parametrize(
        ("grade", "expected"),
        [
            (AirQualityGrade.GOOD, 0.0),
            (AirQualityGrade.MODERATE, 0.25),
            (AirQualityGrade.BAD, 0.70),
            (AirQualityGrade.VERY_BAD, 1.00),
        ],
    )
    def test_pm_grade_loads(self, grade: AirQualityGrade, expected: float) -> None:
        assert pm_load(grade, CONFIG)[0] == pytest.approx(expected)

    def test_missing_air_grade_is_zero_and_degraded(self) -> None:
        load, degraded = pm_load(None, CONFIG)
        assert load == 0.0
        assert degraded == (WellnessDegraded.PM_UNAVAILABLE,)

    @pytest.mark.parametrize("celsius", [5.0, 20.0, 28.0])
    def test_comfort_band_is_zero(self, celsius: float) -> None:
        assert thermal_load(celsius, RainBucket.NONE, CONFIG)[0] == 0.0

    def test_heat_boundary_saturates(self) -> None:
        assert thermal_load(33.0, RainBucket.NONE, CONFIG)[0] == pytest.approx(1.0)
        assert thermal_load(40.0, RainBucket.NONE, CONFIG)[0] == pytest.approx(1.0)

    def test_cold_boundary_saturates(self) -> None:
        assert thermal_load(-12.0, RainBucket.NONE, CONFIG)[0] == pytest.approx(1.0)

    def test_heavy_rain_adds_to_thermal_load(self) -> None:
        dry = thermal_load(20.0, RainBucket.NONE, CONFIG)[0]
        wet = thermal_load(20.0, RainBucket.HEAVY, CONFIG)[0]
        assert dry == 0.0
        assert wet == pytest.approx(0.30)

    def test_light_rain_does_not_add(self) -> None:
        assert thermal_load(20.0, RainBucket.LIGHT, CONFIG)[0] == 0.0

    def test_outdoor_is_capped_at_the_configured_ceiling(self) -> None:
        assert outdoor_load(60, CONFIG)[0] == pytest.approx(0.5)
        assert outdoor_load(120, CONFIG)[0] == pytest.approx(1.0)
        assert outdoor_load(600, CONFIG)[0] == pytest.approx(1.0)

    def test_missing_route_returns_none(self) -> None:
        load, degraded = outdoor_load(None, CONFIG)
        assert load is None
        assert degraded == (WellnessDegraded.OUTDOOR_UNAVAILABLE,)

    def test_interest_multiplier_is_a_step(self) -> None:
        preferences = [
            WellnessPreference(wellness_topic=WellnessTopic.UV, is_enabled=True),
        ]
        assert interest_multiplier(
            preferences, frozenset({WellnessTopic.UV}), CONFIG
        ) == pytest.approx(1.25)
        assert interest_multiplier(
            preferences, frozenset({WellnessTopic.PM}), CONFIG
        ) == pytest.approx(1.0)

    def test_disabled_preference_does_not_boost(self) -> None:
        preferences = [
            WellnessPreference(wellness_topic=WellnessTopic.UV, is_enabled=False),
        ]
        assert interest_multiplier(
            preferences, frozenset({WellnessTopic.UV}), CONFIG
        ) == pytest.approx(1.0)


# ──────────────────────────────────────────────────────────────────────────────
# 양자화 (§7.2, inputHash와 공유)
# ──────────────────────────────────────────────────────────────────────────────


class TestQuantization:
    @pytest.mark.parametrize(
        ("probability", "expected"),
        [
            (0, RainBucket.NONE),
            (29, RainBucket.NONE),
            (30, RainBucket.LIGHT),
            (59, RainBucket.LIGHT),
            (60, RainBucket.HEAVY),
            (100, RainBucket.HEAVY),
        ],
    )
    def test_rain_boundaries(self, probability: int, expected: RainBucket) -> None:
        assert quantize(env(precipitationProbability=probability), CONFIG).rain is expected

    @pytest.mark.parametrize(
        ("index", "expected"),
        [(0.0, UvBucket.LOW), (5.9, UvBucket.LOW), (6.0, UvBucket.HIGH)],
    )
    def test_uv_boundary(self, index: float, expected: UvBucket) -> None:
        assert quantize(env(uvIndex=index), CONFIG).uv is expected

    def test_moderate_air_shares_the_good_bucket(self) -> None:
        assert quantize(env(airGrade="moderate"), CONFIG).pm is PmBucket.GOOD
        assert quantize(env(airGrade="bad"), CONFIG).pm is PmBucket.BAD
        assert quantize(env(airGrade="very_bad"), CONFIG).pm is PmBucket.VERY_BAD

    @pytest.mark.parametrize(
        ("celsius", "expected"),
        [
            (4.9, TempBucket.COLD),
            (5.0, TempBucket.MILD),
            (28.0, TempBucket.MILD),
            (28.1, TempBucket.HOT),
        ],
    )
    def test_temp_boundaries(self, celsius: float, expected: TempBucket) -> None:
        assert quantize(env(feelsLikeCelsius=celsius), CONFIG).temp is expected

    def test_temp_swing_flag(self) -> None:
        wide = env(feelsLikeMinCelsius=8.0, feelsLikeMaxCelsius=20.0)
        narrow = env(feelsLikeMinCelsius=15.0, feelsLikeMaxCelsius=20.0)
        assert quantize(wide, CONFIG).temp_swing is True
        assert quantize(narrow, CONFIG).temp_swing is False


# ──────────────────────────────────────────────────────────────────────────────
# 밴드와 행동 (§7.1, §7.3, PRD §14.6)
# ──────────────────────────────────────────────────────────────────────────────


class TestBandsAndActions:
    @pytest.mark.parametrize(
        ("score", "expected"),
        [
            (0, WellnessBand.LOW),
            (39, WellnessBand.LOW),
            (40, WellnessBand.MID),
            (69, WellnessBand.MID),
            (70, WellnessBand.HIGH),
            (100, WellnessBand.HIGH),
        ],
    )
    def test_band_edges(self, score: int, expected: WellnessBand) -> None:
        assert wis_band(score, CONFIG) is expected

    def test_mid_band_caps_actions_at_two(self) -> None:
        result = evaluate_wellness(
            build(
                environment={
                    "uvIndex": 7.0,
                    "airGrade": "bad",
                    "feelsLikeCelsius": 30.0,
                    "precipitationProbability": 40,
                },
                estimatedOutdoorMinutes=30,
            )
        )
        assert result.wis_band is WellnessBand.MID
        assert len(result.actions) == 2

    def test_rain_triggers_gear_action(self) -> None:
        """강수는 T에만 기여하므로 다른 부하가 함께 있어야 카드 밴드에 오른다.

        비만 오는 날은 WIS가 낮아 카드에 뜨지 않고, 우산은 Plan Engine의
        강수 체크리스트가 담당한다 (§5.4 · 절대 원칙 6 알림 예산).
        """
        result = evaluate_wellness(
            build(
                environment={
                    "uvIndex": 8.0,
                    "airGrade": "good",
                    "feelsLikeCelsius": 20.0,
                    "precipitationProbability": 70,
                },
                estimatedOutdoorMinutes=120,
            )
        )
        codes = [action.action_code for action in result.actions]
        assert result.wis_band is WellnessBand.MID
        assert WellnessActionCode.RAIN_GEAR.value in codes

    def test_rain_alone_stays_below_the_card_band(self) -> None:
        result = evaluate_wellness(
            build(
                environment={
                    "uvIndex": 0.0,
                    "airGrade": "good",
                    "feelsLikeCelsius": 20.0,
                    "precipitationProbability": 70,
                },
                estimatedOutdoorMinutes=120,
            )
        )
        assert result.wis_band is WellnessBand.LOW
        assert result.actions == []

    def test_temp_swing_triggers_cold_prep_even_when_mild(self) -> None:
        result = evaluate_wellness(
            build(
                environment={
                    "uvIndex": 7.0,
                    "airGrade": "good",
                    "feelsLikeCelsius": 20.0,
                    "precipitationProbability": 0,
                    "feelsLikeMinCelsius": 8.0,
                    "feelsLikeMaxCelsius": 22.0,
                },
                estimatedOutdoorMinutes=120,
            )
        )
        codes = [action.action_code for action in result.actions]
        assert WellnessActionCode.TEMP_COLD_PREP.value in codes

    def test_one_prep_item_merges_into_one_action_only(self) -> None:
        """물 하나가 두 행동에 동시에 병합되지 않는다."""
        result = evaluate_wellness(
            build(
                environment={
                    "uvIndex": 7.0,
                    "airGrade": "good",
                    "feelsLikeCelsius": 31.0,
                    "precipitationProbability": 0,
                },
                estimatedOutdoorMinutes=120,
                existingPrepItems=[
                    PrepItemSnapshot(
                        item_id="water",
                        item_name="텀블러 물",
                        action_type="carry",
                        source_type="rule",
                    ).model_dump(by_alias=True)
                ],
            )
        )
        merged = [action.merged_item_id for action in result.actions if action.merged_item_id]
        assert merged.count("water") <= 1

    def test_whitespace_and_case_are_normalized_when_merging(self) -> None:
        result = evaluate_wellness(
            build(
                environment={
                    "uvIndex": 9.0,
                    "airGrade": "good",
                    "feelsLikeCelsius": 24.0,
                    "precipitationProbability": 0,
                },
                estimatedOutdoorMinutes=105,
                existingPrepItems=[
                    PrepItemSnapshot(
                        item_id="uv-item",
                        item_name="  자외선차단  크림 ",
                        action_type="carry",
                        source_type="rule",
                    ).model_dump(by_alias=True)
                ],
            )
        )
        assert result.actions[0].merged_item_id == "uv-item"


# ──────────────────────────────────────────────────────────────────────────────
# TR-11 게이트 (§7.4)
# ──────────────────────────────────────────────────────────────────────────────


def _armable_payload(**state: Any) -> WellnessInput:
    event_state: dict[str, Any] = {
        "wellnessEventEnabled": True,
        "eventInProgress": True,
        "outdoorRemainingMinutes": 40,
        "minutesSinceLastEvent": 180,
    }
    event_state.update(state)
    return build(
        environment={
            "uvIndex": 9.0,
            "airGrade": "bad",
            "feelsLikeCelsius": 31.0,
            "precipitationProbability": 10,
        },
        estimatedOutdoorMinutes=90,
        userPreferences=[
            {
                "wellnessTopic": "uv",
                "isEnabled": True,
                "remindIntervalMinutes": 120,
                "dailyEventCap": 1,
            }
        ],
        eventState=event_state,
    )


def _two_topic_payload(**state: Any) -> WellnessInput:
    """자외선과 수분 보충이 모두 후보인 페이로드.

    후보 순위는 기여도 순으로 `uv_reapply` → `pm_recheck` → `hydration_intake`이고,
    미세먼지 선호가 없으므로 `pm_recheck`는 동의 게이트에서 걸린다. 자외선이 막히면
    수분 보충이 예약돼야 한다.
    """
    event_state: dict[str, Any] = {
        "wellnessEventEnabled": True,
        "eventInProgress": True,
        "outdoorRemainingMinutes": 40,
    }
    event_state.update(state)
    return build(
        environment={
            "uvIndex": 9.0,
            "airGrade": "bad",
            "feelsLikeCelsius": 31.0,
            "precipitationProbability": 10,
        },
        estimatedOutdoorMinutes=90,
        userPreferences=[
            {
                "wellnessTopic": topic,
                "isEnabled": True,
                "remindIntervalMinutes": 120,
                "dailyEventCap": 1,
            }
            for topic in ("uv", "hydration")
        ],
        eventState=event_state,
    )


class TestArmingGates:
    def test_all_gates_open(self) -> None:
        result = evaluate_wellness(_armable_payload())
        assert result.event_armed is True
        assert result.armed_action_code == WellnessActionCode.UV_REAPPLY.value

    def test_consent_gate(self) -> None:
        result = evaluate_wellness(_armable_payload(wellnessEventEnabled=False))
        assert result.event_armed is False
        assert ArmingGate.CONSENT.value in result.arming_blocked_by

    def test_exposure_gate_when_indoor_transition_estimated(self) -> None:
        result = evaluate_wellness(_armable_payload(indoorTransitionEstimated=True))
        assert ArmingGate.EXPOSURE.value in result.arming_blocked_by

    def test_exposure_gate_when_no_outdoor_time_left(self) -> None:
        result = evaluate_wellness(_armable_payload(outdoorRemainingMinutes=0))
        assert ArmingGate.EXPOSURE.value in result.arming_blocked_by

    def test_interval_gate(self) -> None:
        result = evaluate_wellness(_armable_payload(minutesSinceLastEvent=60))
        assert ArmingGate.INTERVAL.value in result.arming_blocked_by

    def test_completed_gate(self) -> None:
        result = evaluate_wellness(_armable_payload(completedActionCodes=["uv_reapply"]))
        assert ArmingGate.ALREADY_HANDLED.value in result.arming_blocked_by

    def test_daily_cap_gate(self) -> None:
        result = evaluate_wellness(_armable_payload(dailyEventCount=1))
        assert ArmingGate.DAILY_CAP.value in result.arming_blocked_by

    def test_raised_threshold_blocks_a_score_that_would_otherwise_pass(self) -> None:
        """D9 — 해제율이 높은 항목은 임계가 70에서 85로 올라간다."""
        payload = build(
            environment={
                "uvIndex": 10.0,
                "airGrade": "bad",
                "feelsLikeCelsius": 20.0,
                "precipitationProbability": 0,
            },
            estimatedOutdoorMinutes=60,
            userPreferences=[
                {
                    "wellnessTopic": "uv",
                    "isEnabled": True,
                    "remindIntervalMinutes": 60,
                    "dailyEventCap": 1,
                }
            ],
            eventState={
                "wellnessEventEnabled": True,
                "eventInProgress": True,
                "outdoorRemainingMinutes": 30,
                "minutesSinceLastEvent": 120,
                "raisedThresholdActionCodes": ["uv_reapply"],
            },
        )
        result = evaluate_wellness(payload)
        assert result.wis_score is not None
        assert 70 <= result.wis_score < 85
        assert result.event_armed is False
        assert ArmingGate.SCORE.value in result.arming_blocked_by

    def test_no_environment_means_no_candidate(self) -> None:
        result = evaluate_wellness(build(environment=None))
        assert result.arming_blocked_by == [ArmingGate.NO_CANDIDATE.value]
        assert result.degraded == [WellnessDegraded.ENV_UNAVAILABLE.value]


class TestPerTopicGates:
    """게이트 ④·⑥은 항목별이다 (TRD §7.4).

    ``daily_event_cap``은 ``USER_WELLNESS_PREF``의 topic별 컬럼이고, §7.4는 "일정당
    상한과 별개다. 하루에 야외 일정이 3건이어도 같은 항목으로 3번 알리지 않는다"로
    정했다. 스칼라 하나로 판정하면 아침에 받은 자외선 알림이 그날 수분 보충 알림까지
    막는다.
    """

    def test_daily_cap_is_per_topic(self) -> None:
        """자외선 상한 소진이 수분 보충을 막지 않는다."""
        result = evaluate_wellness(
            _two_topic_payload(
                topicStates={
                    "uv": {"dailyEventCount": 1, "minutesSinceLastEvent": 200},
                    "hydration": {"dailyEventCount": 0},
                }
            )
        )
        assert result.event_armed is True
        assert result.armed_action_code == WellnessActionCode.HYDRATION_INTAKE.value

    def test_interval_is_per_topic(self) -> None:
        """자외선 주기가 아직 안 됐어도 수분 보충은 나갈 수 있다."""
        result = evaluate_wellness(
            _two_topic_payload(
                topicStates={
                    "uv": {"dailyEventCount": 0, "minutesSinceLastEvent": 10},
                    "hydration": {"dailyEventCount": 0, "minutesSinceLastEvent": 300},
                }
            )
        )
        assert result.event_armed is True
        assert result.armed_action_code == WellnessActionCode.HYDRATION_INTAKE.value

    def test_stop_today_is_limited_to_the_exact_action_code(self) -> None:
        """자외선 stop_today가 hydration 후보를 차단하지 않는다."""
        result = evaluate_wellness(
            _two_topic_payload(
                topicStates={
                    "uv": {"dailyEventCount": 0, "minutesSinceLastEvent": 300},
                    "hydration": {"dailyEventCount": 0, "minutesSinceLastEvent": 300},
                },
                stopTodayActionCodes=["uv_reapply"],
            )
        )
        assert result.event_armed is True
        assert result.armed_action_code == WellnessActionCode.HYDRATION_INTAKE.value

    def test_all_topics_capped_blocks_everything(self) -> None:
        result = evaluate_wellness(
            _two_topic_payload(
                topicStates={
                    "uv": {"dailyEventCount": 1},
                    "hydration": {"dailyEventCount": 1},
                }
            )
        )
        assert result.event_armed is False
        # 최상위 후보(자외선)의 게이트를 보고한다 — 사용자가 받았을 알림이 그것이다.
        assert ArmingGate.DAILY_CAP.value in result.arming_blocked_by

    def test_topic_state_without_interval_means_never_sent(self) -> None:
        """topic 상태를 줬으면 온전히 준 것으로 본다 — 스칼라로 되돌아가지 않는다."""
        result = evaluate_wellness(
            _two_topic_payload(
                minutesSinceLastEvent=1,  # 스칼라는 주기 미달
                topicStates={"uv": {"dailyEventCount": 0}},
            )
        )
        assert result.event_armed is True
        assert result.armed_action_code == WellnessActionCode.UV_REAPPLY.value

    def test_scalar_still_applies_when_topic_state_absent(self) -> None:
        """M0·M3 페이로드 호환 — topicStates가 없으면 스칼라로 판정한다."""
        armed = evaluate_wellness(_two_topic_payload(minutesSinceLastEvent=300))
        blocked = evaluate_wellness(_two_topic_payload(minutesSinceLastEvent=10))
        assert armed.event_armed is True
        assert blocked.event_armed is False
        assert ArmingGate.INTERVAL.value in blocked.arming_blocked_by

    def test_scalar_daily_count_blocks_every_topic(self) -> None:
        """스칼라만 쓰면 모든 항목이 함께 막힌다 — 이 동작이 per-topic 도입의 근거다."""
        result = evaluate_wellness(
            _two_topic_payload(minutesSinceLastEvent=300, dailyEventCount=1)
        )
        assert result.event_armed is False
        assert ArmingGate.DAILY_CAP.value in result.arming_blocked_by

    def test_unknown_topic_key_is_rejected(self) -> None:
        """정의되지 않은 topic 키는 계약 불일치이므로 통과시키지 않는다."""
        with pytest.raises(ValidationError):
            _two_topic_payload(topicStates={"nonsense": {"dailyEventCount": 0}})


# ──────────────────────────────────────────────────────────────────────────────
# RLS · DWL
# ──────────────────────────────────────────────────────────────────────────────


class TestRushLoad:
    def test_full_load_saturates_at_100(self) -> None:
        result = compute_rush_load_score(
            prep_delay_minutes=999.0,
            depart_delay_minutes=999.0,
            critical_alert_count=99,
            config=CONFIG,
        )
        assert result.score == 100
        assert result.prep_delay_norm == 1.0

    def test_no_delay_is_zero(self) -> None:
        result = compute_rush_load_score(
            prep_delay_minutes=0.0,
            depart_delay_minutes=0.0,
            critical_alert_count=0,
            config=CONFIG,
        )
        assert result.score == 0


class TestDailyLoad:
    def test_missing_wis_renormalizes_instead_of_scoring_zero(self) -> None:
        events = [
            DailyEventSummary(event_id="e1", rush_load_score=80, outdoor_minutes=10),
        ]
        daily = summarize_daily_load(events=events, config=CONFIG)
        # 0.4 × 80 = 32 would understate a rushed day; renormalising gives 80.
        assert daily.dwl_score == 80
        assert WellnessDegraded.WIS_UNAVAILABLE in daily.degraded

    def test_zero_outdoor_falls_back_to_unweighted_mean(self) -> None:
        events = [
            DailyEventSummary(event_id="e1", wis_score=80, outdoor_minutes=0),
            DailyEventSummary(event_id="e2", wis_score=40, outdoor_minutes=0),
        ]
        daily = summarize_daily_load(events=events, config=CONFIG)
        assert daily.avg_wis_weighted == 60.0

    def test_density_beats_exposure(self) -> None:
        events = [
            DailyEventSummary(
                event_id=f"e{index}", wis_score=30, rush_load_score=10, outdoor_minutes=60
            )
            for index in range(4)
        ]
        daily = summarize_daily_load(events=events, config=CONFIG)
        assert daily.card_scenario is not None
        assert daily.card_scenario.value == "density"
