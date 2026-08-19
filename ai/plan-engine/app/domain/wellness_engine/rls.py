"""RLS — 촉박함 부담 점수 (TRD §7.1 · PRD §14.4).

    RLS = min(100, 100 × (0.45·Dp + 0.35·Dd + 0.20·E))

RLS는 스트레스나 정신건강을 측정하지 않습니다. 일정이 얼마나 촉박하게 수행됐는지 나타내는
운영 지표이며, 다음 계획과 사후 메시지의 강도를 조절하는 데만 씁니다 (절대 원칙 3).

PRD는 Dp·Dd를 "0~1로 정규화"라고만 적었습니다. 정규화 척도
``rls_delay_full_load_minutes``(기본 30분)와 ``rls_critical_alert_full_count``(기본 2회)는
제안값이며 확정이 필요합니다. 이른 출발은 촉박함이 아니므로 음수 지연은 0으로 봅니다.
"""

from dataclasses import dataclass

from app.contracts.config import WellnessEngineConfig


@dataclass(frozen=True)
class RushLoad:
    score: int
    prep_delay_norm: float
    depart_delay_norm: float
    critical_alert_norm: float


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


def compute_rush_load_score(
    *,
    prep_delay_minutes: float,
    depart_delay_minutes: float,
    critical_alert_count: int,
    config: WellnessEngineConfig,
) -> RushLoad:
    scale = float(config.rls_delay_full_load_minutes)
    dp = _clamp01(max(0.0, prep_delay_minutes) / scale)
    dd = _clamp01(max(0.0, depart_delay_minutes) / scale)
    e = _clamp01(critical_alert_count / float(config.rls_critical_alert_full_count))

    weighted = (
        config.rls_weight_dp * dp + config.rls_weight_dd * dd + config.rls_weight_e * e
    )
    return RushLoad(
        score=min(100, round(100.0 * weighted)),
        prep_delay_norm=round(dp, 4),
        depart_delay_norm=round(dd, 4),
        critical_alert_norm=round(e, 4),
    )
