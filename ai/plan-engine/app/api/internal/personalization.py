"""Personalization Engine internal endpoint (M2).

The transport layer owns request identity and logging so the domain layer stays
pure.  The M0 ``STUB_MODE`` gate is gone: the engine is implemented, so this
route always computes (contract doc §11 step 1–3).
"""

import logging
import time
from uuid import uuid4

from fastapi import APIRouter, Request

from app.contracts.personalization import (
    PersonalizationInput,
    PersonalizationOutput,
)
from app.domain.personalization_engine.engine import adjust
from app.domain.personalization_engine.version import MODEL_VERSION

router = APIRouter(tags=["personalization"])
logger = logging.getLogger("engine.personalization")

REQUEST_ID_HEADER = "X-Request-Id"


@router.post(
    "/internal/v1/personalization/adjust",
    response_model=PersonalizationOutput,
    response_model_by_alias=True,
)
def adjust_personalization(
    payload: PersonalizationInput,
    request: Request,
) -> PersonalizationOutput:
    """Attribute the delay cause and adjust one knob (TRD §6)."""
    request_id = request.headers.get(REQUEST_ID_HEADER) or str(uuid4())
    started_at = time.perf_counter()
    result = adjust(payload)
    duration_ms = (time.perf_counter() - started_at) * 1000

    # Decisions only.  Not the event id, not the observed timestamps: the log
    # must not become a second copy of a user's morning (§14 최소 수집).
    logger.info(
        "personalization_adjusted request_id=%s model_version=%s cause=%s knob=%s "
        "excluded=%s exclusion_reasons=%s degraded=%s duration_ms=%.3f",
        request_id,
        MODEL_VERSION,
        result.cause.value,
        result.adjusted_knob.value,
        result.excluded_from_learning,
        ",".join(result.exclusion_reasons) or "-",
        ",".join(result.degraded) or "-",
        duration_ms,
    )
    return result
