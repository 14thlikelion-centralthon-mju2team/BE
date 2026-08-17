"""requirements.txt must stay in sync with pyproject.toml.

Teammates install from requirements.txt while the Docker image installs from
pyproject.toml, so a silent drift would mean CI and local runs test different
dependency versions.
"""

import tomllib
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]


def requirement_lines() -> set[str]:
    text = (PROJECT_ROOT / "requirements.txt").read_text(encoding="utf-8")
    return {
        line.strip()
        for line in text.splitlines()
        if line.strip() and not line.strip().startswith("#")
    }


def pyproject_dependencies() -> set[str]:
    data = tomllib.loads((PROJECT_ROOT / "pyproject.toml").read_text(encoding="utf-8"))
    project = data["project"]
    declared = set(project["dependencies"])
    declared.update(project["optional-dependencies"]["dev"])
    return declared


def test_requirements_match_pyproject() -> None:
    assert requirement_lines() == pyproject_dependencies()


def test_every_dependency_is_pinned() -> None:
    for requirement in requirement_lines():
        assert "==" in requirement, f"unpinned dependency: {requirement}"
