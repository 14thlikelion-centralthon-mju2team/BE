"""Tests for the golden fixture schema and loader."""

from pathlib import Path

import pytest
from pydantic import ValidationError

from app.contracts.fixtures import GoldenFixture, load_fixture, load_fixtures_from_dir

FIXTURES_DIR = Path(__file__).parent.parent / "fixtures"


# ──────────────────────────────────────────────────────────────────────────────
# Positive cases
# ──────────────────────────────────────────────────────────────────────────────


class TestGoldenFixtureSchema:
    def test_valid_fixture(self):
        f = GoldenFixture.model_validate(
            {
                "caseId": "00_test",
                "description": "Test fixture",
                "engine": "plan",
                "contractVersion": "m0-v1",
                "algorithmVersion": "stub",
                "configVersion": "m0",
                "input": {"now": "2026-08-18T12:00:00+09:00"},
                "expected": {"feasible": True},
                "tags": ["contract", "smoke"],
            }
        )
        assert f.case_id == "00_test"
        assert f.engine == "plan"
        assert f.tags == ["contract", "smoke"]

    def test_empty_tags_allowed(self):
        f = GoldenFixture.model_validate(
            {
                "caseId": "01_no_tags",
                "description": "No tags",
                "engine": "wellness",
                "contractVersion": "m0-v1",
                "algorithmVersion": "stub",
                "configVersion": "m0",
                "input": {},
                "expected": {},
            }
        )
        assert f.tags == []

    def test_json_roundtrip(self):
        data = {
            "caseId": "rt_test",
            "description": "Roundtrip",
            "engine": "personalization",
            "contractVersion": "m0-v1",
            "algorithmVersion": "stub",
            "configVersion": "m0",
            "input": {"eventId": "e1"},
            "expected": {"cause": "unknown"},
            "tags": ["roundtrip"],
        }
        f = GoldenFixture.model_validate(data)
        json_str = f.model_dump_json(by_alias=True)
        f2 = GoldenFixture.model_validate_json(json_str)
        assert f == f2


# ──────────────────────────────────────────────────────────────────────────────
# Negative cases
# ──────────────────────────────────────────────────────────────────────────────


class TestGoldenFixtureInvalid:
    def test_missing_case_id(self):
        with pytest.raises(ValidationError):
            GoldenFixture.model_validate(
                {
                    "description": "no id",
                    "engine": "plan",
                    "contractVersion": "m0-v1",
                    "algorithmVersion": "stub",
                    "configVersion": "m0",
                    "input": {},
                    "expected": {},
                }
            )

    def test_invalid_engine(self):
        with pytest.raises(ValidationError):
            GoldenFixture.model_validate(
                {
                    "caseId": "bad_engine",
                    "description": "Bad",
                    "engine": "invalid_engine",
                    "contractVersion": "m0-v1",
                    "algorithmVersion": "stub",
                    "configVersion": "m0",
                    "input": {},
                    "expected": {},
                }
            )

    def test_missing_contract_version(self):
        with pytest.raises(ValidationError):
            GoldenFixture.model_validate(
                {
                    "caseId": "no_ver",
                    "description": "No version",
                    "engine": "plan",
                    "algorithmVersion": "stub",
                    "configVersion": "m0",
                    "input": {},
                    "expected": {},
                }
            )

    def test_empty_case_id_rejected(self):
        with pytest.raises(ValidationError):
            GoldenFixture.model_validate(
                {
                    "caseId": "",
                    "description": "Empty id",
                    "engine": "plan",
                    "contractVersion": "m0-v1",
                    "algorithmVersion": "stub",
                    "configVersion": "m0",
                    "input": {},
                    "expected": {},
                }
            )


# ──────────────────────────────────────────────────────────────────────────────
# Smoke fixtures from disk
# ──────────────────────────────────────────────────────────────────────────────


class TestSmokeFixtures:
    def test_smoke_fixtures_exist(self):
        assert FIXTURES_DIR.exists(), f"Fixtures directory missing: {FIXTURES_DIR}"
        files = list(FIXTURES_DIR.glob("contract_smoke_*.json"))
        assert len(files) >= 3, f"Expected at least 3 smoke fixtures, found {len(files)}"

    def test_all_smoke_fixtures_valid(self):
        for p in sorted(FIXTURES_DIR.glob("contract_smoke_*.json")):
            fixture = load_fixture(p)
            assert fixture.case_id.startswith("00_contract_smoke")
            assert fixture.contract_version == "m0-v1"

    def test_load_fixtures_from_dir(self):
        fixtures = load_fixtures_from_dir(FIXTURES_DIR)
        assert len(fixtures) >= 3
        for f in fixtures:
            assert f.case_id
            assert f.engine in ("plan", "personalization", "wellness")
