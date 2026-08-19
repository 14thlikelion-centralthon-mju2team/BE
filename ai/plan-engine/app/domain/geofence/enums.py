"""지오펜스 판정 관련 enum (TRD §9.2)."""

from enum import StrEnum


class DestinationKind(StrEnum):
    """목적지 유형별 반경 (§9.2).

    실내로 들어가면 마지막 fix가 부정확해지므로 지하철·복합시설은 반경을 넓게 둡니다.
    """

    #: 지상 건물·일반 POI — 100m
    GROUND_POI = "ground_poi"
    #: 지하철역·지하상가·복합시설 — 200m
    SUBWAY_OR_COMPLEX = "subway_or_complex"
    #: 판별 불가 — 기본값 150m
    UNKNOWN = "unknown"


class ArrivalDecision(StrEnum):
    """신뢰도 구간별 처리 (§9.2, §9.3).

    ``AUTO_CONFIRM``이면 확인 UI를 띄우지 않습니다. PRD §12.10의 "충분히 판단할 수 있는
    데이터가 있으면 반복 질문을 생략한다"가 구현상 이 뜻입니다.
    """

    #: ≥ AUTO_CONF — 자동 확정, 질문 없음
    AUTO_CONFIRM = "auto_confirm"
    #: 사이 구간 — 홈 카드에서 조용한 확인 요청 (푸시 아님)
    QUIET_CONFIRM = "quiet_confirm"
    #: < 하한 — unresolved
    UNRESOLVED = "unresolved"
