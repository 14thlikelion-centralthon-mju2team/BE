"""Enums local to the wellness engine.

``WellnessTopic`` and ``WellnessBand`` are part of the frozen contract and live
in ``app.contracts.common``; they are not redefined here.

The string values below are new in M3.  They travel in ``list[str]`` contract
fields (``degraded``, ``armingBlockedBy``, ``actionCode``, ``cardScenario``),
so adding codes is non-breaking (contract doc §10).
"""

from enum import StrEnum


class WellnessActionCode(StrEnum):
    """The approved action catalogue (TR-09, D6, PRD §14.6).

    Nothing outside this enum can reach a user.  Free generation is banned, so
    the copy lint (§17.5) has a finite list to check.

    Pre-departure actions go on the 준비 카드.  In-event actions are the only
    ones eligible for a wellness push (§7.4).
    """

    # 외출 전 (준비 카드)
    UV_PROTECT = "uv_protect"
    PM_MASK = "pm_mask"
    TEMP_HEAT_PREP = "temp_heat_prep"
    TEMP_COLD_PREP = "temp_cold_prep"
    RAIN_GEAR = "rain_gear"
    # 일정 중 (푸시 후보)
    UV_REAPPLY = "uv_reapply"
    PM_RECHECK = "pm_recheck"
    HYDRATION_INTAKE = "hydration_intake"


#: Action codes eligible for a wellness event push (PRD §14.6 "일정 중 이벤트").
#: 한파 has no in-event action — PRD says "기본적으로 추가 푸시 없음".
IN_EVENT_ACTION_CODES = frozenset(
    {
        WellnessActionCode.UV_REAPPLY,
        WellnessActionCode.PM_RECHECK,
        WellnessActionCode.HYDRATION_INTAKE,
    }
)


class RainBucket(StrEnum):
    """`rain : none | light(≥30%) | heavy(≥60%)` (§7.2)."""

    NONE = "none"
    LIGHT = "light"
    HEAVY = "heavy"


class UvBucket(StrEnum):
    """`uv : low | high(≥6)` (§7.2)."""

    LOW = "low"
    HIGH = "high"


class PmBucket(StrEnum):
    """`pm : good | bad | veryBad` (§7.2).

    The air grade has four levels but the hash bucket has three: 좋음 and 보통
    lead to the same decision, so they share ``good``.
    """

    GOOD = "good"
    BAD = "bad"
    VERY_BAD = "veryBad"


class TempBucket(StrEnum):
    """`temp : cold | mild | hot` (§7.2), with a separate 일교차 flag."""

    COLD = "cold"
    MILD = "mild"
    HOT = "hot"


class ArmingGate(StrEnum):
    """The TR-11 gates, reported when one of them blocks a push (§7.4)."""

    #: ① 동의 — both opt-ins default to false (D4).
    CONSENT = "consent"
    #: ② 점수 — WIS below the (possibly raised) threshold.
    SCORE = "score"
    #: ③ 노출 — event not running, no outdoor exposure left, or indoor inferred.
    EXPOSURE = "exposure"
    #: ④ 주기 — the user's remind interval has not elapsed.
    INTERVAL = "interval"
    #: ⑤ 미완료 — already completed, or stopped for today.
    ALREADY_HANDLED = "already_handled"
    #: ⑥ 일일 상한 — the per-topic daily cap is spent.
    DAILY_CAP = "daily_cap"
    #: No in-event action was triggered by the environment at all.
    NO_CANDIDATE = "no_candidate"


class WellnessDegraded(StrEnum):
    """What was missing and what the engine assumed instead (§7.2, §11.5)."""

    #: No environment snapshot at all — no WIS, time plan unaffected.
    ENV_UNAVAILABLE = "env_unavailable"
    #: 자외선지수 없음 → U=0.
    UV_UNAVAILABLE = "uv_unavailable"
    #: 대기질 등급 없음 → P=0.
    PM_UNAVAILABLE = "pm_unavailable"
    #: 체감온도 없음 → T=0.
    TEMP_UNAVAILABLE = "temp_unavailable"
    #: 강수확률 없음 → rain bucket none.
    RAIN_UNAVAILABLE = "rain_unavailable"
    #: 경로 없음 → WIS 자체를 생략 (§7.2).
    OUTDOOR_UNAVAILABLE = "outdoor_unavailable"
    #: 야외 시간이 추정값이라 카드에 수치를 쓰지 않는다 (§7.5).
    OUTDOOR_ESTIMATED = "outdoor_estimated"
    #: 일정별 WIS가 없어 DWL의 환경 항을 만들 수 없다.
    WIS_UNAVAILABLE = "wis_unavailable"
    #: 일정별 RLS가 없어 DWL의 촉박함 항을 만들 수 없다.
    RLS_UNAVAILABLE = "rls_unavailable"
    #: A config key the calculation reads was omitted, so its default was used.
    CONFIG_FALLBACK = "config_fallback"


class CardScenario(StrEnum):
    """Daily card template, chosen by fixed priority (§7.5).

    `rushed > density > exposure > stable > default`
    """

    RUSHED = "rushed"
    DENSITY = "density"
    EXPOSURE = "exposure"
    STABLE = "stable"
    DEFAULT = "default"
