import logging

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.api.internal.personalization import router as personalization_router
from app.api.internal.wellness import router as wellness_router
from app.api.routes.plan import REQUEST_ID_HEADER
from app.api.routes.plan import router as plan_router
from app.domain.personalization_engine.version import MODEL_VERSION as PERSONALIZATION_VERSION
from app.domain.plan_engine.engine import PlanInputError
from app.domain.plan_engine.version import CALC_VERSION

logger = logging.getLogger("plan_engine")

app = FastAPI(
    title="Ensom AI Engine",
    version=CALC_VERSION,
    description="Internal AI engine service — Plan (M1), Personalization (M2), Wellness (M3).",
)
app.include_router(plan_router)
app.include_router(personalization_router)
app.include_router(wellness_router)


@app.get("/health")
def health() -> dict[str, str]:
    return {
        "status": "ok",
        "calcVersion": CALC_VERSION,
        "personalizationModelVersion": PERSONALIZATION_VERSION,
    }


@app.exception_handler(PlanInputError)
async def plan_input_error_handler(request: Request, exc: PlanInputError) -> JSONResponse:
    """Input that validates field-wise but cannot be computed."""
    return JSONResponse(
        status_code=422,
        content={"code": "INVALID_PLAN_INPUT", "message": str(exc)},
    )


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    # Log the failure without a traceback: frames can hold request payloads,
    # which may contain sensitive preparation items (prompt §21).
    logger.error(
        "plan_compute_failed request_id=%s path=%s error_type=%s",
        request.headers.get(REQUEST_ID_HEADER) or "-",
        request.url.path,
        type(exc).__name__,
    )
    return JSONResponse(
        status_code=500,
        content={"code": "INTERNAL_ERROR", "message": "내부 계산 오류가 발생했습니다."},
    )
