"""Golden fixture schema and loader.

Provides a shared format for golden test cases across Plan (01~06),
Personalization, and Wellness engines.  M0 defines the schema and smoke
fixtures; M1/M2/M3 add real algorithm results without changing the format.
"""

import json
from pathlib import Path

from pydantic import Field

from app.contracts.base import ContractModel
from app.contracts.common import EngineType


class GoldenFixture(ContractModel):
    """Schema for a single golden test case file."""

    case_id: str = Field(min_length=1)
    description: str = Field(min_length=1)
    engine: EngineType
    contract_version: str = Field(min_length=1)
    algorithm_version: str = Field(min_length=1)
    config_version: str = Field(min_length=1)
    input: dict  # type: ignore[type-arg]
    expected: dict  # type: ignore[type-arg]
    tags: list[str] = Field(default_factory=list)


def load_fixture(path: Path) -> GoldenFixture:
    """Load and validate a golden fixture JSON file."""
    raw = json.loads(path.read_text(encoding="utf-8"))
    return GoldenFixture.model_validate(raw)


def load_fixtures_from_dir(directory: Path) -> list[GoldenFixture]:
    """Load all ``*.json`` fixtures in a directory, sorted by filename."""
    fixtures: list[GoldenFixture] = []
    for p in sorted(directory.glob("*.json")):
        fixtures.append(load_fixture(p))
    return fixtures
