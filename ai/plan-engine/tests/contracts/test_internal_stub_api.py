"""Tests for internal stub API endpoints (Personalization + Wellness)."""

import pytest

from app.main import app


@pytest.fixture
def client():
    from starlette.testclient import TestClient

    return TestClient(app)


# ──────────────────────────────────────────────────────────────────────────────
# Health
# ──────────────────────────────────────────────────────────────────────────────


def test_health(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert "calcVersion" in data


# ──────────────────────────────────────────────────────────────────────────────
# Personalization engine (M2 — real logic, no stub gate)
# ──────────────────────────────────────────────────────────────────────────────


def _personalization_payload() -> dict:
    return {
        "eventId": "evt-001",
        "planned": {
            "prepStartAt": "2026-08-18T12:00:00+09:00",
            "recommendedDepartAt": "2026-08-18T12:40:00+09:00",
            "targetArriveAt": "2026-08-18T13:30:00+09:00",
            "estimatedPrepMinutes": 30,
            "travelMinutes": 40,
            "trafficBufferMinutes": 5,
        },
        "actual": {
            "actualPrepStartedAt": "2026-08-18T12:05:00+09:00",
            "actualDepartedAt": "2026-08-18T12:50:00+09:00",
            "actualArrivedAt": "2026-08-18T13:35:00+09:00",
            "resultSource": "geofence",
        },
        "outcome": {
            "arrivalResult": "late",
            "rushAssessment": "rushed",
        },
        "currentEstimate": {
            "estimatedMinutes": 30.0,
            "sampleCount": 5,
            "confidence": 0.8,
            "modelVersion": "v1",
        },
        "config": {"modelVersion": "v1"},
    }


def test_personalization_200(client):
    resp = client.post("/internal/v1/personalization/adjust", json=_personalization_payload())
    assert resp.status_code == 200
    data = resp.json()
    assert data["contractVersion"] == "m0-v1"
    assert data["modelVersion"] == "m2-personalization-1.0.0"
    # Started 5 min late, prep ran 5 min long, travel 5 min over: a three-way
    # tie that the causal-chain tie-break resolves to the earliest link.
    assert data["cause"] == "prep_late"
    assert data["adjustedKnob"] == "notification_lead"
    assert data["excludedFromLearning"] is False
    # No actualPrepFinishedAt in the payload, so lingering is not measurable.
    assert "prep_finish_unknown" in data["degraded"]


def test_personalization_endpoint_is_not_stub_gated(client, monkeypatch):
    """M2 removed the STUB_MODE gate — the engine always computes."""
    import app.api.internal.personalization as personalization_module

    assert not hasattr(personalization_module, "_STUB_MODE")

    monkeypatch.setenv("STUB_MODE", "false")
    resp = client.post("/internal/v1/personalization/adjust", json=_personalization_payload())
    assert resp.status_code == 200


def test_personalization_response_is_camel_case(client):
    resp = client.post("/internal/v1/personalization/adjust", json=_personalization_payload())
    data = resp.json()
    # All keys should be camelCase
    assert "contractVersion" in data
    assert "adjustedKnob" in data
    assert "excludedFromLearning" in data
    # snake_case should NOT appear
    assert "contract_version" not in data
    assert "adjusted_knob" not in data


def test_personalization_invalid_request_422(client):
    resp = client.post("/internal/v1/personalization/adjust", json={"invalid": "data"})
    assert resp.status_code == 422


def test_personalization_naive_datetime_422(client):
    payload = _personalization_payload()
    payload["planned"]["prepStartAt"] = "2026-08-18T12:00:00"  # no offset
    resp = client.post("/internal/v1/personalization/adjust", json=payload)
    assert resp.status_code == 422


# ──────────────────────────────────────────────────────────────────────────────
# Wellness stub
# ──────────────────────────────────────────────────────────────────────────────


def _wellness_payload() -> dict:
    return {
        "environment": {
            "precipitationProbability": 30,
            "feelsLikeCelsius": 28.5,
            "observedAt": "2026-08-18T12:00:00+09:00",
        },
        "estimatedOutdoorMinutes": 45,
        "userPreferences": [
            {
                "wellnessTopic": "uv",
                "isEnabled": True,
                "remindIntervalMinutes": 120,
                "dailyEventCap": 1,
            }
        ],
        "existingPrepItems": [],
        "config": {"weightVersion": "w1"},
    }


def test_wellness_stub_200(client):
    resp = client.post("/internal/v1/wellness/evaluate", json=_wellness_payload())
    assert resp.status_code == 200
    data = resp.json()
    assert data["contractVersion"] == "m0-v1"
    assert data["eventArmed"] is False
    assert data["weightVersion"] == "stub"


def test_wellness_response_is_camel_case(client):
    resp = client.post("/internal/v1/wellness/evaluate", json=_wellness_payload())
    data = resp.json()
    assert "contractVersion" in data
    assert "eventArmed" in data
    assert "weightVersion" in data
    assert "contract_version" not in data


def test_wellness_null_environment_returns_degraded(client):
    payload = _wellness_payload()
    payload["environment"] = None
    resp = client.post("/internal/v1/wellness/evaluate", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert "env_unavailable" in data["degraded"]


def test_wellness_invalid_request_422(client):
    resp = client.post("/internal/v1/wellness/evaluate", json={"bad": "input"})
    assert resp.status_code == 422


def test_wellness_invalid_enum_422(client):
    payload = _wellness_payload()
    payload["userPreferences"][0]["wellnessTopic"] = "invalid_topic"
    resp = client.post("/internal/v1/wellness/evaluate", json=payload)
    assert resp.status_code == 422


# ──────────────────────────────────────────────────────────────────────────────
# No stack trace in error responses
# ──────────────────────────────────────────────────────────────────────────────


def test_error_no_stack_trace(client):
    resp = client.post("/internal/v1/personalization/adjust", json={"broken": True})
    assert resp.status_code == 422
    body = resp.text
    assert "Traceback" not in body
    assert "File " not in body
