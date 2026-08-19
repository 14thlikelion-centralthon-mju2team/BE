"""Wellness Engine contract models.

Defines the input/output schema for WIS (event wellness priority), action
selection, environmental load normalization, RLS and DWL (TRD §7).  M0 froze
the ``evaluate`` contract; M3 implements the scoring and adds the two
aggregation endpoints.

Key rules documented in this contract:
- WIS · RLS · DWL are notification priority values, NOT health scores
  (절대 원칙 3, TRD §7.1).
- Actions are capped at 3 per event (ERD ``ck_wellness_rank``).
- No free-form LLM text — only approved action codes and template copy (TR-09).
- Missing environment data is not an error; it results in degraded output.
- Sensitive prep items are never recommended by the engine.

M3 additions to ``evaluate`` are additive only (contract doc §10): every new
input field is optional with a default, and every new output field has a
default, so an M0 client keeps working.  The gate inputs (§7.4) are new because
the M0 contract could not express TR-11 — it had no way to say "the event is in
progress", "this action already fired today", or "the user pressed stop_today".
"""

from datetime import date

from pydantic import Field, field_validator

from app.contracts.base import ContractModel
from app.contracts.common import WellnessBand, WellnessTopic
from app.contracts.config import WellnessEngineConfig
from app.domain.plan_engine.enums import PrepActionType, PrepSourceType
from app.domain.plan_engine.models import EnvironmentSnapshot

CONTRACT_VERSION = "m0-v1"

MAX_WELLNESS_ACTIONS = 3

# ──────────────────────────────────────────────────────────────────────────────
# Input models — evaluate
# ──────────────────────────────────────────────────────────────────────────────


class PrepItemSnapshot(ContractModel):
    """Prep item already in the user's checklist (shared shape with plan)."""

    item_id: str = Field(min_length=1)
    item_name: str = Field(min_length=1)
    action_type: PrepActionType
    source_type: PrepSourceType
    applied_minutes: int = Field(default=0, ge=0)
    is_sensitive: bool = False


class WellnessPreference(ContractModel):
    """Per-topic user preference for wellness notifications.

    ``is_enabled`` defaults to false in the Backend (D4 opt-in): the user has to
    turn a topic on and pick an interval before anything fires.
    """

    wellness_topic: WellnessTopic
    is_enabled: bool
    remind_interval_minutes: int | None = Field(default=None, ge=1)
    daily_event_cap: int = Field(default=1, ge=0)


class WellnessTopicState(ContractModel):
    """한 topic의 오늘 상태 — ``USER_WELLNESS_PREF`` 행 하나에 대응 (TRD §7.4).

    게이트 ④ 주기와 ⑥ 일일 상한은 **항목별**입니다. §7.4가 "일정당 상한과 별개다.
    하루에 야외 일정이 3건이어도 같은 항목으로 3번 알리지 않는다"로 못 박았고,
    ``daily_event_cap``도 ``USER_WELLNESS_PREF``의 topic별 컬럼입니다.

    이 객체를 주면 같은 topic의 스칼라 필드보다 우선합니다. 여기서
    ``minutes_since_last_event``가 없으면 "이 항목은 오늘 한 번도 보내지 않았다"는
    뜻이고, 스칼라로 되돌아가지 않습니다 — topic 상태를 줬다면 온전히 준 것으로 봅니다.
    """

    #: 오늘 이 topic으로 발송한 이벤트 수.
    daily_event_count: int = Field(default=0, ge=0)
    #: 이 topic의 마지막 발송 이후 경과 분.  None이면 발송 이력 없음.
    minutes_since_last_event: int | None = Field(default=None, ge=0)


class WellnessEventState(ContractModel):
    """Runtime state the TR-11 gates need (TRD §7.4).

    All fields default to the most conservative value, so an M0 payload that
    omits this object can never arm a push.
    """

    #: ``USER_SETTING.wellness_event_enabled`` — gate ①, opt-in (D4).
    wellness_event_enabled: bool = False
    #: Gate ③ — the event is currently running.
    event_in_progress: bool = False
    #: Gate ③ — outdoor exposure still ahead, in minutes.
    outdoor_remaining_minutes: int | None = Field(default=None, ge=0)
    #: Gate ③ — cancel when an indoor transition is inferred.
    indoor_transition_estimated: bool = False
    #: Gate ④ — minutes since the last event.  None means none was ever sent,
    #: which satisfies the interval.  **Per-topic 값이 있으면 그쪽이 우선한다** —
    #: ``topic_states`` 참고.
    minutes_since_last_event: int | None = Field(default=None, ge=0)
    #: Gate ⑤ — action codes already completed for this event.
    completed_action_codes: list[str] = Field(default_factory=list)
    #: Gate ⑤ · backoff — action codes the user stopped for today.
    stop_today_action_codes: list[str] = Field(default_factory=list)
    #: Gate ⑥ — events already sent today.  **Per-topic 값이 있으면 그쪽이 우선한다.**
    daily_event_count: int = Field(default=0, ge=0)
    #: D9 — action codes whose WIS threshold was auto-raised to 85 after high
    #: opt-out or not-relevant rates.
    raised_threshold_action_codes: list[str] = Field(default_factory=list)

    # M4 addition — 게이트 ④·⑥이 항목별이라는 사실을 스칼라로는 표현할 수 없었다.
    #: topic별 오늘 상태.  키가 있는 topic은 스칼라 대신 이 값을 쓴다.
    topic_states: dict[WellnessTopic, WellnessTopicState] = Field(default_factory=dict)


class WellnessInput(ContractModel):
    """Full input to the wellness engine for a single event evaluation."""

    environment: EnvironmentSnapshot | None = None
    estimated_outdoor_minutes: int | None = Field(default=None, ge=0)
    user_preferences: list[WellnessPreference] = Field(default_factory=list)
    existing_prep_items: list[PrepItemSnapshot] = Field(default_factory=list)
    config: WellnessEngineConfig

    # M3 addition
    event_state: WellnessEventState = Field(default_factory=WellnessEventState)


# ──────────────────────────────────────────────────────────────────────────────
# Output models — evaluate
# ──────────────────────────────────────────────────────────────────────────────


class NormalizedWellnessLoads(ContractModel):
    """Normalized environmental load factors (0.0~1.0 each)."""

    uv_load: float = Field(ge=0.0, le=1.0)
    pm_load: float = Field(ge=0.0, le=1.0)
    thermal_load: float = Field(ge=0.0, le=1.0)
    outdoor_load: float = Field(ge=0.0, le=1.0)
    interest_multiplier: float = Field(ge=0.0)


class QuantizedEnvironment(ContractModel):
    """Environment buckets shared with the plan engine's ``inputHash`` (§5.5).

    Cutting at the boundaries where behaviour changes is the point: 61% and 63%
    precipitation produce the same decision, so hashing the raw value would
    create a new plan revision every five minutes.
    """

    rain: str = Field(min_length=1)
    uv: str = Field(min_length=1)
    pm: str = Field(min_length=1)
    temp: str = Field(min_length=1)
    temp_swing: bool = False


class WellnessAction(ContractModel):
    """A single wellness action recommendation.

    - ``action_code`` must come from an approved set (no free-form text).
    - ``action_label`` is the user-facing display string (template-based).
    - ``display_rank`` determines ordering (1 = highest priority).
    - ``reason`` is a structured explanation, not LLM-generated.
    """

    wellness_topic: WellnessTopic
    action_code: str = Field(min_length=1)
    action_label: str = Field(min_length=1)
    display_rank: int = Field(ge=1, le=MAX_WELLNESS_ACTIONS)
    reason: str = Field(min_length=1)

    # M3 additions
    #: True when an existing prep item covered this action.  The user's item
    #: keeps its ``source_type='rule'``; only the wellness reason is attached
    #: (§5.4, 골든 09).
    merged_with_prep_item: bool = False
    #: ``item_id`` of the prep item this action merged into, when merged.
    merged_item_id: str | None = None


class WellnessOutput(ContractModel):
    """Result of the wellness engine evaluation.

    ``wis_score`` and ``wis_band`` may be None if environment data is
    insufficient — this is a ``degraded`` condition, not an error.
    """

    wis_score: int | None = Field(default=None, ge=0, le=100)
    wis_band: WellnessBand | None = None
    normalized_loads: NormalizedWellnessLoads | None = None
    actions: list[WellnessAction] = Field(default_factory=list)
    event_armed: bool
    weight_version: str = Field(min_length=1)
    contract_version: str = Field(default=CONTRACT_VERSION, min_length=1)
    degraded: list[str] = Field(default_factory=list)

    # M3 additions
    quantized: QuantizedEnvironment | None = None
    #: The single action code armed for a push, when ``event_armed`` is true.
    #: A schedule row carries one action code (ERD ``uq_wellness_event_once``).
    armed_action_code: str | None = None
    #: Which TR-11 gates blocked arming.  Empty when armed.
    arming_blocked_by: list[str] = Field(default_factory=list)

    @field_validator("actions")
    @classmethod
    def max_three_actions(cls, v: list[WellnessAction]) -> list[WellnessAction]:
        if len(v) > MAX_WELLNESS_ACTIONS:
            raise ValueError(f"actions must contain at most {MAX_WELLNESS_ACTIONS} items")
        return v


# ──────────────────────────────────────────────────────────────────────────────
# RLS — 촉박함 부담 (TRD §7.1 · PRD §14.4)
# ──────────────────────────────────────────────────────────────────────────────


class RushLoadInput(ContractModel):
    """Observed execution deltas for one completed event.

    RLS does not measure stress or mental health.  It records how rushed the
    execution was and only modulates the next plan and the closing message
    (PRD §14.4).
    """

    event_id: str = Field(min_length=1)
    #: ``actualPrepStartedAt − prepStartAt`` in minutes (signed).
    prep_delay_minutes: float = 0.0
    #: ``actualDepartedAt − recommendedDepartAt`` in minutes (signed).
    depart_delay_minutes: float = 0.0
    #: Number of 극한 알림 sent for this event.
    critical_alert_count: int = Field(default=0, ge=0)
    config: WellnessEngineConfig


class RushLoadOutput(ContractModel):
    """RLS and its three normalized components (ERD ``EVENT_EXECUTION``)."""

    event_id: str = Field(min_length=1)
    rush_load_score: int = Field(ge=0, le=100)
    prep_delay_norm: float = Field(ge=0.0, le=1.0)
    depart_delay_norm: float = Field(ge=0.0, le=1.0)
    critical_alert_norm: float = Field(ge=0.0, le=1.0)
    weight_version: str = Field(min_length=1)
    contract_version: str = Field(default=CONTRACT_VERSION, min_length=1)


# ──────────────────────────────────────────────────────────────────────────────
# DWL — 일일 부담과 마무리 카드 (TRD §7.1, §7.5 · PRD §14.5)
# ──────────────────────────────────────────────────────────────────────────────


class DailyEventSummary(ContractModel):
    """One managed event's contribution to the day."""

    event_id: str = Field(min_length=1)
    wis_score: int | None = Field(default=None, ge=0, le=100)
    rush_load_score: int | None = Field(default=None, ge=0, le=100)
    #: Outdoor minutes — the weight of this event's WIS in the daily average.
    outdoor_minutes: int | None = Field(default=None, ge=0)
    #: True when ``outdoor_minutes`` is observed rather than estimated (§7.5).
    outdoor_observed: bool = False


class DailySummaryInput(ContractModel):
    """Input for the daily closing card (§7.5)."""

    summary_date: date
    events: list[DailyEventSummary] = Field(default_factory=list)
    #: Wellness actions proposed and completed today — 행동 완료율 (§16.2).
    proposed_action_count: int = Field(default=0, ge=0)
    completed_action_count: int = Field(default=0, ge=0)
    critical_alert_count: int = Field(default=0, ge=0)
    config: WellnessEngineConfig


class DailySummaryOutput(ContractModel):
    """DWL and the selected card scenario.

    ``dwl_score`` is returned and stored but the client does not display it
    (D5) — a number invites reading it as a health score.  The band is what the
    UI shows.
    """

    summary_date: date
    event_count: int = Field(ge=0)
    total_outdoor_minutes: int | None = Field(default=None, ge=0)
    avg_wis_weighted: float | None = Field(default=None, ge=0.0, le=100.0)
    avg_rls: float | None = Field(default=None, ge=0.0, le=100.0)
    dwl_score: int | None = Field(default=None, ge=0, le=100)
    dwl_band: WellnessBand | None = None
    #: ``default`` / ``exposure`` / ``density`` / ``rushed`` / ``stable``.
    card_scenario: str | None = None
    #: Rendered sentence, preserved for the content review audit trail
    #: (``DAILY_WELLNESS_SUMMARY.card_message_snapshot``, §7.5).
    card_message: str | None = None
    #: False when there was no managed event — the card is not shown (§7.5).
    card_visible: bool = False
    weight_version: str = Field(min_length=1)
    contract_version: str = Field(default=CONTRACT_VERSION, min_length=1)
    degraded: list[str] = Field(default_factory=list)
