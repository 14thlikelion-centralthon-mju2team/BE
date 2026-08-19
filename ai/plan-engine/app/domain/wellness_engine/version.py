"""Wellness weight version.

Bump this when a weight, a normalisation boundary, a band edge, the action
mapping, or an approved template's meaning changes.  The Backend stores it in
``PLAN_WELLNESS_SCORE.weight_version`` and
``DAILY_WELLNESS_SUMMARY``, and metrics are aggregated per version.

**Past scores are never recomputed** (D15, TRD §7.1).  Reproducing an old score
with the engine of the day is the premise of PRD §16.9 explainability.
"""

WEIGHT_VERSION = "m3-wellness-1.0.0"
