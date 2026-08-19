"""``inputHash`` 계산 (TRD §5.5).

```
inputHash = sha256(canonicalJson({
    event.startsAt, origin(lat,lng), destination(lat,lng), sourceType,
    estimatedPrepMinutes, trafficBufferMinutes, arrivalBufferMinutes,
    selectedRoute.id, selectedRoute.totalMinutes, selectedRoute.walkMinutes,
    quantize(context), activePrepItemIdsAndMinutes, calcVersion, weightVersion
}))
```

§5.5는 무엇을 넣을지만 정했습니다. 두 언어가 같은 해시를 내려면 **어떻게 문자열로 만들지**까지
고정해야 하므로, 아래 다섯 가지를 여기서 확정합니다. 하나라도 어긋나면 Java와 Python이 다른 해시를
내고, 리비전이 매 틱 올라가거나 반대로 영원히 안 올라갑니다.

1. **키 정렬과 공백** — 키는 사전순, 구분자는 공백 없는 ``,``·``:``. 중첩 객체의 키도 정렬합니다.
2. **좌표** — 부동소수 표기는 언어마다 다릅니다(`0.1` vs `0.10000000000000001`).
   소수점 6자리로 **절단(0 방향)** 한 고정 소수점 문자열로 넣습니다. 6자리는 약 0.11m로,
   지오펜스 반경 100~200m 기준에서 같은 결정을 낳는 범위입니다.
   반올림이 아니라 절단인 이유는 **"정확히 절반" 규칙을 아예 없애기 위해서**입니다. 반올림을
   고르면 언어별 기본 모드(Python은 round-half-even, Java ``String.format``은 HALF_UP)와
   이진 표현 오차가 경계에서 서로 다른 답을 냅니다.
3. **시각** — UTC로 정규화한 뒤 초 단위 ``...Z``. offset 표기 차이를 없앱니다.
4. **비ASCII** — 이스케이프합니다. 해시 입력에는 식별자·숫자·구간 이름만 들어오므로 실제로는
   등장하지 않지만, 규칙을 비워 두면 나중에 누가 이름을 넣었을 때 조용히 갈라집니다.
5. **음수 0** — 절단 결과가 0이면 부호를 버립니다. Python ``Decimal``은 ``-0.000000``을 표현할 수
   있지만 Java ``BigDecimal``의 0은 부호가 없습니다. 그리니치 근처 경도(예: −0.0000004)에서
   실제로 갈리는 지점입니다.

준비 항목은 ``itemId`` 사전순으로 정렬합니다. 목록 순서가 해시를 바꾸면, 같은 계획이 조회 순서에
따라 다른 해시를 갖게 됩니다.

Java 대조 규칙
--------------

===============  ==============================================================
단계             Python / Java
===============  ==============================================================
변환             ``Decimal(str(v))`` / ``BigDecimal.valueOf(d)``
절단             ``.quantize(Decimal("0.000001"), ROUND_DOWN)`` /
                 ``.setScale(6, RoundingMode.DOWN)``
0 정규화         결과가 0이면 부호 제거 (``BigDecimal``은 자동)
===============  ==============================================================

둘 다 "double을 왕복 가능한 최단 10진 표기로 바꾼 뒤 절단"이라 값이 일치합니다.
**``new BigDecimal(double)``은 쓰지 마십시오** — 이진 오차를 그대로 펼쳐서
``new BigDecimal(37.5665)``가 ``37.56650000000000205...``가 되고, 경계에서 결과가 갈립니다.

기준값은 **JSON에 실린 double**입니다. 좌표를 DB ``numeric``에서 7자리 이상으로 읽어 온다면
DTO 경계에서 먼저 6자리로 맞춰야 합니다 — 그렇지 않으면 "DB의 정확한 10진값"과 "JSON을 지나온
double"이 서로 다른 절단 결과를 낼 수 있습니다.
"""

import hashlib
import json
from datetime import UTC, datetime
from decimal import ROUND_DOWN, Decimal
from typing import Any

from app.domain.revision.models import RevisionSnapshot

#: 공백 없는 구분자. Java의 ``ObjectMapper`` 기본값과 같은 모양이다.
CANONICAL_SEPARATORS = (",", ":")

#: 좌표 절단 자릿수. 6자리 ≈ 0.11m.
COORDINATE_DECIMALS = 6

#: ``Decimal('0.000001')`` — 절단 격자.
COORDINATE_QUANTUM = Decimal(1).scaleb(-COORDINATE_DECIMALS)


def format_coordinate(value: float) -> str:
    """좌표를 소수점 6자리로 절단(0 방향)한 고정 소수점 문자열.

    ``str(value)``를 거치는 이유는 ``Decimal(float)``이 이진 오차를 그대로 펼치기 때문입니다.
    Python의 ``str(float)``과 Java의 ``Double.toString``은 둘 다 "왕복 가능한 최단 10진 표기"를
    내므로, 이 경로가 두 언어를 맞추는 지점입니다.
    """
    truncated = Decimal(str(value)).quantize(COORDINATE_QUANTUM, rounding=ROUND_DOWN)
    if truncated.is_zero():
        # Java BigDecimal 의 0 은 부호가 없다. -0.000000 을 내보내면 그 자리에서 갈린다.
        truncated = abs(truncated)
    return f"{truncated:f}"


def format_instant(value: datetime) -> str:
    """timezone-aware datetime을 UTC 초 단위 ``...Z``로 정규화한다."""
    return value.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def build_hash_input(snapshot: RevisionSnapshot) -> dict[str, Any]:
    """해시에 들어갈 정규화된 딕셔너리. 감사·디버깅 때 그대로 찍어볼 수 있다."""
    return {
        "eventStartsAt": format_instant(snapshot.event_starts_at),
        "originLat": format_coordinate(snapshot.origin.latitude),
        "originLng": format_coordinate(snapshot.origin.longitude),
        "destinationLat": format_coordinate(snapshot.destination.latitude),
        "destinationLng": format_coordinate(snapshot.destination.longitude),
        "sourceType": snapshot.source_type,
        "estimatedPrepMinutes": snapshot.estimated_prep_minutes,
        "trafficBufferMinutes": snapshot.traffic_buffer_minutes,
        "arrivalBufferMinutes": snapshot.arrival_buffer_minutes,
        "routeId": snapshot.selected_route.route_id,
        "routeTotalMinutes": snapshot.selected_route.total_minutes,
        "routeWalkMinutes": snapshot.selected_route.walk_minutes,
        "quantizedRain": snapshot.quantized_context.rain,
        "quantizedUv": snapshot.quantized_context.uv,
        "quantizedPm": snapshot.quantized_context.pm,
        "quantizedTemp": snapshot.quantized_context.temp,
        "quantizedTempSwing": snapshot.quantized_context.temp_swing,
        "activePrepItems": [
            {"itemId": item.item_id, "appliedMinutes": item.applied_minutes}
            for item in sorted(snapshot.active_prep_items, key=lambda item: item.item_id)
        ],
        "calcVersion": snapshot.calc_version,
        "weightVersion": snapshot.weight_version,
    }


def canonical_json(payload: dict[str, Any]) -> str:
    """정렬·무공백·ASCII 이스케이프 JSON."""
    return json.dumps(
        payload,
        sort_keys=True,
        separators=CANONICAL_SEPARATORS,
        ensure_ascii=True,
    )


def compute_input_hash(snapshot: RevisionSnapshot) -> str:
    """``inputHash`` — 소문자 sha256 hex."""
    canonical = canonical_json(build_hash_input(snapshot))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
