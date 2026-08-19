"""출발·도착 판정 신뢰도 참조 구현 (TRD §9.2).

서버는 지오펜스를 실행하지 않습니다 — 판정 결과 수신 API만 제공합니다(§9.2). 따라서 신뢰도
계산도 결과를 받는 Spring 쪽에서 일어나야 하고, 이 패키지는 **정의의 단일 출처와 적합성 벡터**를
제공합니다. M4의 산출물은 "구현"이 아니라 "검증·튜닝"입니다(§18).

엔드포인트는 없습니다.
"""

from app.domain.geofence.confidence import (
    ArrivalObservation,
    ConfidenceBreakdown,
    compute_arrival_confidence,
    destination_radius_meters,
    is_unresolved_by_timeout,
)
from app.domain.geofence.enums import ArrivalDecision, DestinationKind

__all__ = [
    "ArrivalDecision",
    "ArrivalObservation",
    "ConfidenceBreakdown",
    "DestinationKind",
    "compute_arrival_confidence",
    "destination_radius_meters",
    "is_unresolved_by_timeout",
]
