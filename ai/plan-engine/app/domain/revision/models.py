"""``inputHash`` 입력 스냅샷 모델 (TRD §5.5).

해시에 들어가는 값만 담습니다. 일정 제목·본문·참석자는 애초에 들어오지 않습니다
(절대 원칙 8). 좌표는 들어오지만 해시 직전에 고정 소수점으로 절단합니다.
"""

from datetime import datetime

from pydantic import Field, field_validator

from app.domain.plan_engine.models import CamelModel, require_aware


class GeoPoint(CamelModel):
    latitude: float = Field(ge=-90.0, le=90.0)
    longitude: float = Field(ge=-180.0, le=180.0)


class RouteRef(CamelModel):
    route_id: str = Field(min_length=1)
    total_minutes: int = Field(ge=0)
    walk_minutes: int = Field(default=0, ge=0)


class ActivePrepItem(CamelModel):
    """활성 준비 항목의 식별자와 적용 분. 항목 **이름은 넣지 않습니다.**"""

    item_id: str = Field(min_length=1)
    applied_minutes: int = Field(default=0, ge=0)


class QuantizedContext(CamelModel):
    """양자화된 환경 구간 (§7.2와 같은 경계).

    웰니스 엔진의 ``quantize()`` 결과를 그대로 넣습니다. 원값을 넣으면 강수확률 1%p 차이로
    리비전이 올라가므로, 행동이 갈리는 경계로 자른 값만 해시에 들어갑니다.
    """

    rain: str = Field(min_length=1)
    uv: str = Field(min_length=1)
    pm: str = Field(min_length=1)
    temp: str = Field(min_length=1)
    temp_swing: bool = False


class RevisionSnapshot(CamelModel):
    """§5.5가 열거한 해시 입력 전체."""

    event_starts_at: datetime
    origin: GeoPoint
    destination: GeoPoint
    source_type: str = Field(min_length=1)
    estimated_prep_minutes: int = Field(ge=0)
    traffic_buffer_minutes: int = Field(ge=0)
    arrival_buffer_minutes: int = Field(ge=0)
    selected_route: RouteRef
    quantized_context: QuantizedContext
    active_prep_items: list[ActivePrepItem] = Field(default_factory=list)
    calc_version: str = Field(min_length=1)
    weight_version: str = Field(min_length=1)

    _starts_at_aware = field_validator("event_starts_at")(require_aware)
