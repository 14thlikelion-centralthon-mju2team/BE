"""Wellness engine — WIS · RLS · DWL (TRD §7).

Pure domain code: no FastAPI, no DB, no HTTP client, no ``datetime.now()``, no
environment access.  Every weight and boundary arrives through
``WellnessEngineConfig`` (TR-06), and every runtime fact the TR-11 gates need is
injected through the request.

절대 원칙 3 — WIS·RLS·DWL are notification priority values, not health scores.
No diagnosis, treatment, dosage, efficacy or skin judgement is possible here
because the data for it never enters this package.
"""

from app.domain.wellness_engine.engine import (
    compute_rush_load,
    evaluate_wellness,
    summarize_day,
)
from app.domain.wellness_engine.version import WEIGHT_VERSION

__all__ = [
    "WEIGHT_VERSION",
    "compute_rush_load",
    "evaluate_wellness",
    "summarize_day",
]
