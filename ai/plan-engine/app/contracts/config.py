"""Engine configuration contracts.

The Backend ``ENGINE_CONFIG`` table is the single source of truth for all
engine parameters.  The AI server never stores or overrides config — it
validates the values received per-request and uses them as-is.
"""

from pydantic import Field

from app.contracts.base import ContractModel

# ──────────────────────────────────────────────────────────────────────────────
# Plan Engine Config (already exists in domain layer, re-defined here as the
# canonical contract; the domain EngineConfig re-exports from here in M1+).
# ──────────────────────────────────────────────────────────────────────────────


class PlanEngineConfig(ContractModel):
    """Plan Engine runtime configuration passed per-request by Backend."""

    seed_fallback_minutes: int = Field(default=30, ge=0)
    arrival_buffer_default_minutes: int = Field(default=10, ge=0)
    traffic_buffer_default_minutes: int = Field(default=5, ge=0)
    rain_threshold_percent: int = Field(default=60, ge=0, le=100)
    rain_extra_prep_minutes: int = Field(default=5, ge=0)
    calc_version: str = Field(min_length=1)


# ──────────────────────────────────────────────────────────────────────────────
# Personalization Engine Config
# ──────────────────────────────────────────────────────────────────────────────


class PersonalizationEngineConfig(ContractModel):
    """Personalization Engine runtime configuration.

    Controls EMA smoothing, guard-rails, and step limits for prep estimate
    adjustment (TRD §6).

    The fields below the M0 block were added in M2 with defaults, so an M0
    payload keeps validating unchanged (contract doc §10 non-breaking rule).
    They carry the sample-eligibility and cause-attribution thresholds that
    TRD §6.1 states in prose but appendix A does not yet register as keys.
    """

    # ── M0 frozen keys (appendix A.1) ────────────────────────────────────────
    prep_ema_alpha: float = Field(default=0.30, gt=0.0, le=1.0)
    late_weight: float = Field(default=1.50, gt=0.0)
    early_weight: float = Field(default=0.70, gt=0.0)
    max_step_minutes: int = Field(default=15, ge=1)
    cold_step_minutes: int = Field(default=20, ge=1)
    prep_floor_minutes: int = Field(default=10, ge=0)
    prep_ceiling_ratio: float = Field(default=2.0, gt=0.0)
    model_version: str = Field(min_length=1)

    # ── M2 additions (defaults keep M0 payloads valid) ───────────────────────
    #: Seed used when ``currentEstimate.seedMinutes`` is absent — mirrors the
    #: plan engine's SEED_FALLBACK_MIN (TRD §6.2).
    seed_fallback_minutes: int = Field(default=30, ge=0)
    #: sampleCount below this stays on the seed (TRD §6.2 cold start).
    cold_start_sample_threshold: int = Field(default=3, ge=0)
    #: EVENT_ACTION_LOG clock skew tolerance in seconds (TRD §6.1, TR-02).
    clock_skew_tolerance_seconds: int = Field(default=120, ge=0)
    #: Observed prep duration above this is an outlier ("pressed start and
    #: forgot") and is dropped from learning (TRD §6.1).
    prep_outlier_max_minutes: int = Field(default=240, ge=1)
    #: Minimum geofence confidence for ``resultSource='geo'`` samples
    #: (TRD §6.1 출처 신뢰, appendix A.3 AUTO_CONF).
    geo_min_confidence: float = Field(default=0.60, ge=0.0, le=1.0)
    #: A delay signal below this many minutes is noise, not a cause.
    attribution_min_signal_minutes: int = Field(default=3, ge=0)


# ──────────────────────────────────────────────────────────────────────────────
# Wellness Engine Config
# ──────────────────────────────────────────────────────────────────────────────


class WellnessEngineConfig(ContractModel):
    """Wellness Engine runtime configuration.

    Controls WIS weight distribution and band thresholds (TRD §7).
    WIS is NOT a health score — it is a notification priority value only.

    The M0 block is appendix A.2 as frozen.  The M3 blocks add the boundaries
    that TRD §7.2 states as "확정 제안" prose and the RLS/DWL parameters from
    appendix A.2, all with defaults so an M0 payload keeps validating
    (contract doc §10 non-breaking rule).
    """

    # ── M0 frozen keys (appendix A.2) ────────────────────────────────────────
    wis_weight_uv: float = Field(default=0.35, ge=0.0, le=1.0)
    wis_weight_pm: float = Field(default=0.25, ge=0.0, le=1.0)
    wis_weight_temp: float = Field(default=0.20, ge=0.0, le=1.0)
    wis_weight_outdoor: float = Field(default=0.20, ge=0.0, le=1.0)
    interest_boost_max: float = Field(default=1.25, gt=0.0)
    outdoor_cap_minutes: int = Field(default=120, ge=1)
    wis_band_card: int = Field(default=40, ge=0, le=100)
    wis_band_event: int = Field(default=70, ge=0, le=100)
    weight_version: str = Field(min_length=1)

    # ── M3: input normalisation boundaries (TRD §7.2) ────────────────────────
    #: UV quantisation boundary — ``UV_HIGH`` in appendix A.2.
    uv_high_index: float = Field(default=6.0, ge=0.0)
    #: UV index at which the load saturates at 1.0.  §7.2 anchors the ramp at
    #: 0→0 · 6→0.6 · 8→0.8 · 11+→1.0; a linear ramp through those points
    #: reaches 1.0 at 10, so 11+ is saturated too.
    uv_full_load_index: float = Field(default=10.0, gt=0.0)
    #: Air-quality grade loads: 좋음 0 · 보통 · 나쁨 · 매우나쁨 (§7.2).
    pm_load_moderate: float = Field(default=0.25, ge=0.0, le=1.0)
    pm_load_bad: float = Field(default=0.70, ge=0.0, le=1.0)
    pm_load_very_bad: float = Field(default=1.00, ge=0.0, le=1.0)
    #: Comfort band where the thermal load is 0 (5~28℃ in §7.2).
    comfort_min_celsius: float = Field(default=5.0)
    comfort_max_celsius: float = Field(default=28.0)
    #: Feels-like temperature where the thermal load reaches 1.0.  The heat and
    #: cold advisory boundaries (33℃ / −12℃) are a proposal — §7.2 says
    #: "폭염·한파 경계" without a number, so these need team confirmation.
    heat_extreme_celsius: float = Field(default=33.0)
    cold_extreme_celsius: float = Field(default=-12.0)
    #: Precipitation quantisation boundaries — ``RAIN_LIGHT`` / ``RAIN_HEAVY``.
    rain_light_percent: int = Field(default=30, ge=0, le=100)
    rain_heavy_percent: int = Field(default=60, ge=0, le=100)
    #: Heavy rain adds this to the thermal load before clamping (§7.2).
    rain_thermal_bonus: float = Field(default=0.30, ge=0.0, le=1.0)
    #: Day/night swing that raises the 일교차 flag used by the temp bucket.
    temp_swing_flag_celsius: float = Field(default=10.0, ge=0.0)

    # ── M3: wellness event gates (TRD §7.4 · TR-11 · D9) ────────────────────
    #: ``WELLNESS_EVENT_MIN`` — WIS floor for a push candidate.
    wellness_event_min: int = Field(default=70, ge=0, le=100)
    #: ``WELLNESS_EVENT_MIN_RAISED`` — applied per action code after opt-out
    #: or not-relevant rates cross their limits (D9).
    wellness_event_min_raised: int = Field(default=85, ge=0, le=100)
    #: ``DAILY_EVENT_CAP_DEFAULT`` — used when the preference omits its cap.
    daily_event_cap_default: int = Field(default=1, ge=0)
    #: Card actions shown in the mid band.  PRD §14.3 says "행동 1~2개" for
    #: 40~69 and the high band keeps the ERD ceiling of 3 (``ck_wellness_rank``).
    mid_band_action_cap: int = Field(default=2, ge=0, le=3)

    # ── M3: RLS (TRD §7.1 · PRD §14.4) ──────────────────────────────────────
    rls_weight_dp: float = Field(default=0.45, ge=0.0, le=1.0)
    rls_weight_dd: float = Field(default=0.35, ge=0.0, le=1.0)
    rls_weight_e: float = Field(default=0.20, ge=0.0, le=1.0)
    #: Delay in minutes that normalises to 1.0.  PRD §14.4 asks for a 0~1
    #: normalisation without giving the scale — proposal, needs confirmation.
    rls_delay_full_load_minutes: int = Field(default=30, ge=1)
    #: Critical alert count that normalises to 1.0.
    rls_critical_alert_full_count: int = Field(default=2, ge=1)

    # ── M3: DWL and the daily card (TRD §7.1, §7.5 · PRD §14.5) ─────────────
    dwl_weight_wis: float = Field(default=0.60, ge=0.0, le=1.0)
    dwl_weight_rls: float = Field(default=0.40, ge=0.0, le=1.0)
    #: ``DWL_BANDS = [40, 70]`` → low / mid / high.
    dwl_band_mid: int = Field(default=40, ge=0, le=100)
    dwl_band_high: int = Field(default=70, ge=0, le=100)
    #: Card scenario thresholds (§7.5).  The priority order is fixed in code:
    #: rushed > density > exposure > stable > default.
    card_rushed_rls: int = Field(default=70, ge=0, le=100)
    card_density_event_count: int = Field(default=4, ge=1)
    card_exposure_outdoor_minutes: int = Field(default=90, ge=1)


# ──────────────────────────────────────────────────────────────────────────────
# Geofence Config (M4 — appendix A.3)
# ──────────────────────────────────────────────────────────────────────────────


class GeofenceConfig(ContractModel):
    """출발·도착 판정 신뢰도 파라미터 (TRD §9.2 · 부록 A.3).

    서버는 지오펜스를 실행하지 않고 판정 결과만 받습니다(§9.2). 이 설정은 받은 관측을
    신뢰도로 바꾸는 계수이며, 전부 원격 설정입니다(TR-06).
    """

    #: 기준점 — 아무 가점도 없는 관측의 신뢰도.
    base_confidence: float = Field(default=0.50, ge=0.0, le=1.0)
    #: 체류 조건 충족 가점.
    dwell_bonus: float = Field(default=0.20, ge=0.0, le=1.0)
    #: 진입 시 수평 정확도 양호 가점.
    accuracy_bonus: float = Field(default=0.15, ge=0.0, le=1.0)
    #: 진입 시각이 예상 도착 근처일 때의 가점.
    timing_bonus: float = Field(default=0.15, ge=0.0, le=1.0)
    #: 경계 진동 감점.  진동을 억제하지 않고 신뢰도를 깎는다 — 진동 자체가
    #: "판정이 불확실하다"는 정보이기 때문이다 (§9.2).
    oscillation_penalty: float = Field(default=0.30, ge=0.0, le=1.0)

    #: ``DWELL_SEC`` — 체류 검증 초.
    dwell_seconds: int = Field(default=90, ge=0)
    #: 수평 정확도 양호 기준(m).
    accuracy_good_meters: float = Field(default=50.0, gt=0.0)
    #: 예상 도착 대비 허용 폭(분).
    timing_window_minutes: int = Field(default=20, ge=0)
    #: 이 창(초) 안에 진입/이탈이 반복되면 경계 진동으로 본다.
    oscillation_window_seconds: int = Field(default=60, ge=0)

    #: ``AUTO_CONF`` — 이 값 이상이면 자동 확정하고 확인 UI를 띄우지 않는다 (§9.3).
    auto_confirm_confidence: float = Field(default=0.60, ge=0.0, le=1.0)
    #: 이 값 이상이면 조용한 확인 요청, 미만이면 unresolved.
    quiet_confirm_confidence: float = Field(default=0.40, ge=0.0, le=1.0)

    #: ``GEOFENCE_ORIGIN_R_M`` — 출발지 이탈 반경.
    origin_radius_meters: int = Field(default=150, ge=1)
    #: ``GEOFENCE_DEST_R_M`` — 목적지 유형별 반경.
    destination_radius_ground_meters: int = Field(default=100, ge=1)
    destination_radius_default_meters: int = Field(default=150, ge=1)
    destination_radius_complex_meters: int = Field(default=200, ge=1)

    #: ``UNRESOLVED_AFTER_MIN`` — 일정 시작 후 이만큼 무신호면 unresolved.
    unresolved_after_minutes: int = Field(default=30, ge=1)
