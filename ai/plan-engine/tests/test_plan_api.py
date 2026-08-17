import pytest
from fastapi.testclient import TestClient

import app.api.routes.plan as plan_route
from app.domain.plan_engine.version import CALC_VERSION
from app.main import app

client = TestClient(app)


def request_body() -> dict[str, object]:
    return {
        "now": "2026-08-20T12:00:00+09:00",
        "event": {
            "startsAt": "2026-08-20T14:00:00+09:00",
            "anchorMode": "arrive_by",
        },
        "prepEstimate": {
            "estimatedMinutes": 30,
            "source": "initial_seed",
            "sampleCount": 0,
        },
        "arrivalBufferMinutes": 10,
        "trafficBufferMinutes": 5,
        "selectedRoute": {
            "routeId": "route-1",
            "totalMinutes": 40,
            "walkMinutes": 15,
            "source": "odsay",
            "isStale": False,
        },
        "environment": None,
        "prepItems": [],
        "config": {
            "seedFallbackMinutes": 30,
            "rainThresholdPercent": 60,
            "rainExtraPrepMinutes": 5,
            "arrivalBufferDefaultMinutes": 10,
            "trafficBufferDefaultMinutes": 5,
        },
    }


def test_health() -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_compute_returns_camel_case_json() -> None:
    response = client.post("/internal/v1/plans/compute", json=request_body())

    assert response.status_code == 200
    body = response.json()
    assert body["prepStartAt"] == "2026-08-20T12:35:00+09:00"
    assert body["recommendedDepartAt"] == "2026-08-20T13:05:00+09:00"
    assert body["targetArriveAt"] == "2026-08-20T13:50:00+09:00"
    assert body["breakdown"]["estimatedPrepMinutes"] == 30
    assert "prep_start_at" not in body


def test_naive_datetime_returns_422() -> None:
    body = request_body()
    body["now"] = "2026-08-20T12:00:00"

    response = client.post("/internal/v1/plans/compute", json=body)

    assert response.status_code == 422


def test_negative_minutes_returns_422() -> None:
    body = request_body()
    body["arrivalBufferMinutes"] = -1

    response = client.post("/internal/v1/plans/compute", json=body)

    assert response.status_code == 422


def test_depart_at_without_fixed_departure_returns_422() -> None:
    body = request_body()
    body["event"] = {
        "startsAt": "2026-08-20T14:00:00+09:00",
        "anchorMode": "depart_at",
    }

    response = client.post("/internal/v1/plans/compute", json=body)

    assert response.status_code == 422


def test_infeasible_plan_is_a_successful_domain_response() -> None:
    body = request_body()
    body["now"] = "2026-08-20T13:30:00+09:00"

    response = client.post("/internal/v1/plans/compute", json=body)

    assert response.status_code == 200
    assert response.json()["feasible"] is False
    assert response.json()["recommendedDepartAt"] == "2026-08-20T13:05:00+09:00"


def test_response_reports_calc_version() -> None:
    response = client.post("/internal/v1/plans/compute", json=request_body())

    assert response.status_code == 200
    assert response.json()["calcVersion"] == CALC_VERSION


def test_unknown_field_returns_422() -> None:
    body = request_body()
    body["unexpectedField"] = "value"

    response = client.post("/internal/v1/plans/compute", json=body)

    assert response.status_code == 422


def test_internal_error_returns_500_without_stack_trace(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def explode(_: object) -> None:
        raise RuntimeError("boom: 서연의 복용약")

    monkeypatch.setattr(plan_route, "compute_plan", explode)
    safe_client = TestClient(app, raise_server_exceptions=False)

    response = safe_client.post("/internal/v1/plans/compute", json=request_body())

    assert response.status_code == 500
    assert response.json() == {
        "code": "INTERNAL_ERROR",
        "message": "내부 계산 오류가 발생했습니다.",
    }
    body_text = response.text
    for leak in ("Traceback", "RuntimeError", "boom", "복용약"):
        assert leak not in body_text


def test_health_reports_calc_version() -> None:
    response = client.get("/health")

    assert response.json()["calcVersion"] == CALC_VERSION
