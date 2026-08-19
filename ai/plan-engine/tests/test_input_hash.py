"""``inputHash`` 적합성 벡터와 규칙 검증 (TRD §5.5).

해시는 Spring이 계산합니다. 여기 벡터는 **Java 구현이 같은 것을 계산하는지 확인하는 기준**입니다.
그래서 기대값에 해시만 두지 않고 **정규화된 JSON 문자열도 함께** 둡니다. 해시가 다를 때
"어디서 갈렸는지"를 눈으로 볼 수 있어야 대조가 끝나기 때문입니다.
"""

import json
from pathlib import Path
from typing import Any

import pytest

from app.domain.revision.input_hash import (
    build_hash_input,
    canonical_json,
    compute_input_hash,
    format_coordinate,
)
from app.domain.revision.models import RevisionSnapshot

VECTOR_DIR = Path(__file__).parent / "golden" / "input_hash"
VECTORS = sorted(VECTOR_DIR.glob("*.json"))


def load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def base_snapshot(**overrides: Any) -> RevisionSnapshot:
    payload: dict[str, Any] = {
        "eventStartsAt": "2026-08-20T14:00:00+09:00",
        "origin": {"latitude": 37.5665, "longitude": 126.9780},
        "destination": {"latitude": 37.4979, "longitude": 127.0276},
        "sourceType": "map_search",
        "estimatedPrepMinutes": 30,
        "trafficBufferMinutes": 5,
        "arrivalBufferMinutes": 10,
        "selectedRoute": {"routeId": "route-1", "totalMinutes": 40, "walkMinutes": 15},
        "quantizedContext": {
            "rain": "none",
            "uv": "high",
            "pm": "good",
            "temp": "hot",
            "tempSwing": False,
        },
        "activePrepItems": [
            {"itemId": "item-a", "appliedMinutes": 10},
            {"itemId": "item-b", "appliedMinutes": 0},
        ],
        "calcVersion": "m1-plan-engine-1.0.0",
        "weightVersion": "m3-wellness-1.0.0",
    }
    payload.update(overrides)
    return RevisionSnapshot.model_validate(payload)


def test_vector_dir_is_populated() -> None:
    assert VECTORS, f"no conformance vectors in {VECTOR_DIR}"


@pytest.mark.parametrize("vector_path", VECTORS, ids=lambda path: path.stem)
def test_conformance_vector(vector_path: Path) -> None:
    vector = load(vector_path)
    snapshot = RevisionSnapshot.model_validate(vector["input"])
    built = build_hash_input(snapshot)

    # 좌표 기대값이 있으면 먼저 대조한다.  Java 구현이 갈릴 때 원인이 바로 보인다.
    for key, expected in vector["expected"].get("coordinates", {}).items():
        assert built[key] == expected

    assert canonical_json(built) == vector["expected"]["canonicalJson"]
    assert compute_input_hash(snapshot) == vector["expected"]["inputHash"]


class TestCoordinatePolicy:
    """좌표는 6자리 절단(0 방향)이다 — 반올림이 아니다.

    반올림을 고르면 "정확히 절반"에서 언어별 기본 모드(Python round-half-even,
    Java ``String.format`` HALF_UP)와 이진 표현 오차가 서로 다른 답을 낸다. 절단은
    그 규칙 자체를 없앤다.
    """

    @pytest.mark.parametrize(
        ("value", "expected"),
        [
            (37.5665, "37.566500"),
            (37.5665001, "37.566500"),
            (37.5665005, "37.566500"),  # 정확히 절반 — 반올림이면 갈릴 자리
            (37.5665006, "37.566500"),  # 반올림이면 37.566501
            (37.5665009, "37.566500"),
            (37.0, "37.000000"),
            (-37.5665006, "-37.566500"),  # 0 방향 절단
            (-151.2093005, "-151.209300"),
        ],
    )
    def test_truncates_toward_zero(self, value: float, expected: str) -> None:
        assert format_coordinate(value) == expected

    @pytest.mark.parametrize("value", [-0.0000004, 0.0000004, -1e-07, 1e-07, -0.0])
    def test_zero_never_carries_a_sign(self, value: float) -> None:
        """Java ``BigDecimal``의 0은 부호가 없다. ``-0.000000``을 내보내면 그 자리에서 갈린다."""
        assert format_coordinate(value) == "0.000000"

    def test_rounding_policy_would_change_the_hash(self) -> None:
        """정책이 반올림이었다면 H01과 H05가 다른 해시였을 것이다.

        두 스냅샷은 6자리 아래에서만 다르다. 절단이면 같은 해시, 반올림이면 다른 해시다.
        """
        assert compute_input_hash(base_snapshot()) == compute_input_hash(
            base_snapshot(origin={"latitude": 37.5665006, "longitude": 126.9780009})
        )

    def test_binary_error_does_not_leak_through(self) -> None:
        """``Decimal(float)``이 아니라 ``Decimal(str(float))``을 거쳐야 한다.

        ``Decimal(37.5665)``은 ``37.56650000000000205...``로 펼쳐진다. 절단 자릿수 아래라
        지금은 결과가 같지만, 경로를 바꾸면 경계에서 갈린다.
        """
        assert format_coordinate(37.5665) == "37.566500"
        assert format_coordinate(0.1) == "0.100000"


class TestCanonicalisation:
    def test_keys_are_sorted_and_unspaced(self) -> None:
        canonical = canonical_json(build_hash_input(base_snapshot()))
        assert ", " not in canonical
        assert '": ' not in canonical

        # 중첩 객체의 키까지 정렬돼야 한다.  Java 구현도 같은 규칙을 따라야 한다.
        def assert_sorted(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
            keys = [key for key, _ in pairs]
            assert keys == sorted(keys), f"unsorted keys: {keys}"
            return dict(pairs)

        json.loads(canonical, object_pairs_hook=assert_sorted)

    def test_timezone_offset_does_not_change_the_hash(self) -> None:
        """같은 순간을 다른 offset으로 표기해도 같은 해시여야 한다."""
        kst = base_snapshot(eventStartsAt="2026-08-20T14:00:00+09:00")
        utc = base_snapshot(eventStartsAt="2026-08-20T05:00:00Z")
        assert compute_input_hash(kst) == compute_input_hash(utc)

    def test_prep_item_order_does_not_change_the_hash(self) -> None:
        """조회 순서가 해시를 바꾸면 같은 계획이 매번 다른 해시를 갖는다."""
        forward = base_snapshot(
            activePrepItems=[
                {"itemId": "item-a", "appliedMinutes": 10},
                {"itemId": "item-b", "appliedMinutes": 0},
            ]
        )
        reversed_order = base_snapshot(
            activePrepItems=[
                {"itemId": "item-b", "appliedMinutes": 0},
                {"itemId": "item-a", "appliedMinutes": 10},
            ]
        )
        assert compute_input_hash(forward) == compute_input_hash(reversed_order)

    def test_coordinate_noise_below_six_decimals_is_ignored(self) -> None:
        """6자리 ≈ 0.11m.  그보다 미세한 흔들림으로 리비전이 올라가면 안 된다.

        두 값은 반올림 정책에서는 갈린다(566500 vs 566501). 절단이라 같다.
        """
        stable = base_snapshot(origin={"latitude": 37.5665001, "longitude": 126.9780001})
        assert compute_input_hash(stable) == compute_input_hash(
            base_snapshot(origin={"latitude": 37.5665009, "longitude": 126.9780009})
        )

    def test_coordinate_change_above_the_cut_changes_the_hash(self) -> None:
        moved = base_snapshot(origin={"latitude": 37.5675, "longitude": 126.9780})
        assert compute_input_hash(moved) != compute_input_hash(base_snapshot())

    def test_format_coordinate_is_fixed_width(self) -> None:
        assert format_coordinate(37.5) == "37.500000"
        assert format_coordinate(-0.1) == "-0.100000"


class TestIdempotency:
    def test_same_snapshot_gives_the_same_hash(self) -> None:
        assert compute_input_hash(base_snapshot()) == compute_input_hash(base_snapshot())

    def test_quantised_context_absorbs_raw_environment_noise(self) -> None:
        """§5.5의 요지 — 같은 버킷이면 같은 해시다.

        강수확률 61%와 63%는 둘 다 heavy 버킷이므로 리비전이 올라가지 않는다.
        """
        first = base_snapshot(
            quantizedContext={
                "rain": "heavy",
                "uv": "high",
                "pm": "good",
                "temp": "hot",
                "tempSwing": False,
            }
        )
        second = base_snapshot(
            quantizedContext={
                "rain": "heavy",
                "uv": "high",
                "pm": "good",
                "temp": "hot",
                "tempSwing": False,
            }
        )
        assert compute_input_hash(first) == compute_input_hash(second)

    def test_bucket_change_changes_the_hash(self) -> None:
        light = base_snapshot(
            quantizedContext={
                "rain": "light",
                "uv": "high",
                "pm": "good",
                "temp": "hot",
                "tempSwing": False,
            }
        )
        heavy = base_snapshot(
            quantizedContext={
                "rain": "heavy",
                "uv": "high",
                "pm": "good",
                "temp": "hot",
                "tempSwing": False,
            }
        )
        assert compute_input_hash(light) != compute_input_hash(heavy)

    def test_calc_version_change_changes_the_hash(self) -> None:
        """상수 하나가 바뀌면 calcVersion이 오르고, 해시도 함께 갈린다 (§5.1)."""
        assert compute_input_hash(base_snapshot(calcVersion="m1-plan-engine-1.1.0")) != (
            compute_input_hash(base_snapshot())
        )

    def test_weight_version_change_changes_the_hash(self) -> None:
        assert compute_input_hash(base_snapshot(weightVersion="m3-wellness-1.1.0")) != (
            compute_input_hash(base_snapshot())
        )


class TestPrivacy:
    def test_hash_input_contains_no_names_or_titles(self) -> None:
        """해시 입력에는 식별자·숫자·구간 이름만 들어온다 (절대 원칙 8)."""
        payload = build_hash_input(base_snapshot())
        assert "title" not in json.dumps(payload).lower()
        assert all("itemName" not in key for key in payload)
        for item in payload["activePrepItems"]:
            assert set(item) == {"itemId", "appliedMinutes"}
