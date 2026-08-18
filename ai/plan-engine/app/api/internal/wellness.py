"""Wellness Engine stub endpoint.

M0 contract-only: validates the request against the frozen schema and returns
a stub response.  The actual WIS scoring and action-selection logic is
implemented in M3.

This endpoint is guarded by the STUB_MODE environment variable.
"""

import logging
import os

from fastapi import APIRouter, HTTPException

from app.contracts.wellness import (
    CONTRACT_VERSION,
    WellnessInput,
    WellnessOutput,
)

router = APIRouter(tags=["wellness"])
logger = logging.getLogger("engine.wellness")

_STUB_MODE = os.environ.get("STUB_MODE", "true").lower() == "true"


@router.post(
    "/internal/v1/wellness/evaluate",
    response_model=WellnessOutput,
    response_model_by_alias=True,
)
def evaluate_wellness(payload: WellnessInput) -> WellnessOutput:
    """Validate wellness request and return a stub response.

    In M3 this will compute WIS, select wellness actions, and arm event pushes.
    """
    if not _STUB_MODE:
        raise HTTPException(
            status_code=501,
            detail="Wellness engine not implemented. Set STUB_MODE=true for stub responses.",
        )

    has_environment = payload.environment is not None
    degraded = [] if has_environment else ["env_unavailable"]

    logger.info(
        "wellness_stub has_env=%s prefs=%d contract_version=%s",
        has_environment,
        len(payload.user_preferences),
        CONTRACT_VERSION,
    )

    return WellnessOutput(
        wis_score=None,
        wis_band=None,
        normalized_loads=None,
        actions=[],
        event_armed=False,
        weight_version="stub",
        contract_version=CONTRACT_VERSION,
        degraded=degraded,
    )
