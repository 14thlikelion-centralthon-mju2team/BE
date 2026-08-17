"""Internal compute endpoint.

The transport layer owns the clock, request identity, and logging so the
domain layer stays pure.
"""

import logging
import time
from uuid import uuid4

from fastapi import APIRouter, Request

from app.domain.plan_engine.engine import compute_plan
from app.domain.plan_engine.version import CALC_VERSION
from app.schemas.plan import PlanInput, PlanOutput

router = APIRouter()
logger = logging.getLogger("plan_engine.api")

REQUEST_ID_HEADER = "X-Request-Id"


@router.post(
    "/internal/v1/plans/compute",
    response_model=PlanOutput,
    response_model_by_alias=True,
)
def compute_plan_endpoint(payload: PlanInput, request: Request) -> PlanOutput:
    request_id = request.headers.get(REQUEST_ID_HEADER) or str(uuid4())
    started_at = time.perf_counter()
    result = compute_plan(payload)
    duration_ms = (time.perf_counter() - started_at) * 1000

    # Prompt §21: identifiers and decisions only. Never log event titles,
    # checklist item names, coordinates, or tokens.
    logger.info(
        "plan_computed request_id=%s calc_version=%s anchor_mode=%s feasible=%s "
        "prediction_confidence=%s degraded=%s prep_item_count=%d duration_ms=%.3f",
        request_id,
        CALC_VERSION,
        payload.event.anchor_mode.value,
        result.feasible,
        result.prediction_confidence.value,
        ",".join(reason.value for reason in result.degraded) or "-",
        len(payload.prep_items),
        duration_ms,
    )
    return result
