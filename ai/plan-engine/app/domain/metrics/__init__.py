"""지표 산출 정의 참조 구현 (TRD §16.2).

집계는 Postgres에서 일어납니다 — append-only 이벤트 테이블과 주간 집계 뷰이고, 별도 분석
인프라를 도입하지 않습니다(§16). 그래서 이 패키지도 **엔드포인트를 제공하지 않습니다.**

AI 파트의 산출물은 정의를 한 곳에 고정하고, SQL이 그 정의를 재현하는지 확인할 적합성 벡터를
남기는 것입니다. 임계값 4종은 전부 원격 설정이라 쿼리 수정 없이 재집계할 수 있어야 합니다(TR-06).
README에 같은 정의의 SQL 스케치를 함께 두었습니다.
"""

from app.domain.metrics.north_star import (
    NorthStarConfig,
    NorthStarInput,
    NorthStarOutcome,
    aggregate_north_star,
    evaluate_north_star,
)
from app.domain.metrics.wellness_metrics import (
    WellnessMetricInput,
    WellnessMetrics,
    compute_wellness_metrics,
)

__all__ = [
    "NorthStarConfig",
    "NorthStarInput",
    "NorthStarOutcome",
    "WellnessMetricInput",
    "WellnessMetrics",
    "aggregate_north_star",
    "compute_wellness_metrics",
    "evaluate_north_star",
]
