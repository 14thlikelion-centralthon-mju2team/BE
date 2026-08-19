"""Personalization engine — cause-separated prep estimate correction (TRD §6).

Pure domain code: no FastAPI, no DB, no HTTP client, no ``datetime.now()``, no
environment access.  Every threshold arrives through
``PersonalizationEngineConfig`` (TR-06), and the observed timestamps arrive
through ``PersonalizationInput``.

The package imports its request/response types from ``app.contracts`` because
those models *are* the frozen contract; they are plain Pydantic models with no
transport or storage dependency, so the domain stays pure.
"""

from app.domain.personalization_engine.engine import adjust
from app.domain.personalization_engine.version import MODEL_VERSION

__all__ = ["MODEL_VERSION", "adjust"]
