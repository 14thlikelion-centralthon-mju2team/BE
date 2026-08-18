"""Personalization Engine stub endpoint.

M0 contract-only: validates the request against the frozen schema and returns
a stub response.  The actual cause-separation logic is implemented in M2.

This endpoint is guarded by the STUB_MODE environment variable.  If STUB_MODE
is not "true", the endpoint returns 501 Not Implemented.
"""

import logging
import os

from fastapi import APIRouter, HTTPException

from app.contracts.common import AdjustmentKnob, DelayCause
from app.contracts.personalization import (
    CONTRACT_VERSION,
    PersonalizationInput,
    PersonalizationOutput,
)

router = APIRouter(tags=["personalization"])
logger = logging.getLogger("engine.personalization")

_STUB_MODE = os.environ.get("STUB_MODE", "true").lower() == "true"


@router.post(
    "/internal/v1/personalization/adjust",
    response_model=PersonalizationOutput,
    response_model_by_alias=True,
)
def adjust_personalization(payload: PersonalizationInput) -> PersonalizationOutput:
    """Validate personalization request and return a stub response.

    In M2 this will perform cause-separation and EMA adjustment.
    """
    if not _STUB_MODE:
        raise HTTPException(
            status_code=501,
            detail="Personalization engine not implemented. Set STUB_MODE=true for stub responses.",
        )

    logger.info(
        "personalization_stub event_id=%s contract_version=%s",
        payload.event_id,
        CONTRACT_VERSION,
    )

    return PersonalizationOutput(
        cause=DelayCause.UNKNOWN,
        adjusted_knob=AdjustmentKnob.NONE,
        previous_value=None,
        new_value=None,
        adjustment_reason="M0 stub: no adjustment performed",
        excluded_from_learning=True,
        model_version="stub",
        contract_version=CONTRACT_VERSION,
    )
