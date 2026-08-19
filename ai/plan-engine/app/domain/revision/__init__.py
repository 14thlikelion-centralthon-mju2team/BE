"""재계산 멱등성 — ``inputHash`` 참조 구현 (TRD §5.5).

이 패키지는 **엔드포인트를 제공하지 않습니다.** 30초 틱에서 해시를 얻으려고 AI 서버를 호출하면
§5.5가 노리는 "해시가 같으면 외부 호출 0회"가 무너집니다. 해시는 스냅샷을 이미 들고 있는 Spring이
계산해야 합니다.

그래서 AI 파트의 산출물은 **정의의 단일 출처와 적합성 벡터**입니다.
``tests/golden/input_hash/*.json``의 기대 해시를 Java 구현이 바이트 단위로 재현하면 두 구현이
같은 것을 계산한다고 말할 수 있습니다.
"""

from app.domain.revision.input_hash import (
    CANONICAL_SEPARATORS,
    COORDINATE_DECIMALS,
    COORDINATE_QUANTUM,
    canonical_json,
    compute_input_hash,
    format_coordinate,
)
from app.domain.revision.models import RevisionSnapshot

__all__ = [
    "CANONICAL_SEPARATORS",
    "COORDINATE_DECIMALS",
    "COORDINATE_QUANTUM",
    "RevisionSnapshot",
    "canonical_json",
    "compute_input_hash",
    "format_coordinate",
]
