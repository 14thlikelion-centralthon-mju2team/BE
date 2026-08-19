"""Wellness Engine internal endpoints (M3).

The transport layer owns request identity and logging so the domain layer stays
pure.  The M0 ``STUB_MODE`` gate is gone: the engine is implemented, so these
routes always compute (contract doc §11 step 1–3).

``evaluate`` scores one event.  ``rush-load`` and ``daily-summary`` are the
aggregation endpoints M3 added — the M0 contract had no shape for RLS or DWL,
which TRD §7.1 requires alongside WIS.
"""

import logging
import time
from uuid import uuid4

from fastapi import APIRouter, Request

from app.contracts.wellness import (
    DailySummaryInput,
    DailySummaryOutput,
    RushLoadInput,
    RushLoadOutput,
    WellnessInput,
    WellnessOutput,
)
from app.domain.wellness_engine.engine import (
    compute_rush_load,
    evaluate_wellness,
    summarize_day,
)
from app.domain.wellness_engine.version import WEIGHT_VERSION

router = APIRouter(tags=["wellness"])
logger = logging.getLogger("engine.wellness")

REQUEST_ID_HEADER = "X-Request-Id"


def _request_id(request: Request) -> str:
    return request.headers.get(REQUEST_ID_HEADER) or str(uuid4())


@router.post(
    "/internal/v1/wellness/evaluate",
    response_model=WellnessOutput,
    response_model_by_alias=True,
)
def evaluate_wellness_endpoint(payload: WellnessInput, request: Request) -> WellnessOutput:
    """Compute WIS, select card actions and apply the TR-11 gates (TRD §7)."""
    request_id = _request_id(request)
    started_at = time.perf_counter()
    result = evaluate_wellness(payload)
    duration_ms = (time.perf_counter() - started_at) * 1000

    # Decisions and buckets only.  Never the raw environment values, the place,
    # or a prep item name (절대 원칙 8, §14 최소 수집).
    logger.info(
        "wellness_evaluated request_id=%s weight_version=%s wis_band=%s action_count=%d "
        "event_armed=%s armed_action=%s blocked_by=%s degraded=%s duration_ms=%.3f",
        request_id,
        WEIGHT_VERSION,
        result.wis_band.value if result.wis_band else "-",
        len(result.actions),
        result.event_armed,
        result.armed_action_code or "-",
        ",".join(result.arming_blocked_by) or "-",
        ",".join(result.degraded) or "-",
        duration_ms,
    )
    return result


@router.post(
    "/internal/v1/wellness/rush-load",
    response_model=RushLoadOutput,
    response_model_by_alias=True,
)
def compute_rush_load_endpoint(payload: RushLoadInput, request: Request) -> RushLoadOutput:
    """Compute RLS for one completed event (TRD §7.1)."""
    request_id = _request_id(request)
    result = compute_rush_load(payload)

    logger.info(
        "rush_load_computed request_id=%s weight_version=%s rls=%d",
        request_id,
        WEIGHT_VERSION,
        result.rush_load_score,
    )
    return result


@router.post(
    "/internal/v1/wellness/daily-summary",
    response_model=DailySummaryOutput,
    response_model_by_alias=True,
)
def summarize_day_endpoint(payload: DailySummaryInput, request: Request) -> DailySummaryOutput:
    """Aggregate the day into DWL and pick the closing card (TRD §7.5)."""
    request_id = _request_id(request)
    result = summarize_day(payload)

    # The rendered sentence is stored by the Backend for the content-review
    # audit trail; it is not logged here.
    logger.info(
        "daily_summary_computed request_id=%s weight_version=%s event_count=%d "
        "dwl_band=%s scenario=%s card_visible=%s degraded=%s",
        request_id,
        WEIGHT_VERSION,
        result.event_count,
        result.dwl_band.value if result.dwl_band else "-",
        result.card_scenario or "-",
        result.card_visible,
        ",".join(result.degraded) or "-",
    )
    return result
