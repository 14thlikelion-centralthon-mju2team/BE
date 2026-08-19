"""Personalization model version.

Bump this when the attribution rules, the EMA update, the guard-rails, or the
meaning of an ``adjustmentReason`` sentence change.  The Backend stores it in
``USER_PREP_ESTIMATE.model_version`` so a past correction stays reproducible
and experiments stay comparable (TRD §6.2, D15: no retroactive recomputation).
"""

MODEL_VERSION = "m2-personalization-1.0.0"
