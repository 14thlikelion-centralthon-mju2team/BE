"""M3 wellness evaluation endpoint."""

from fastapi import APIRouter

from app.contracts.wellness import WellnessInput, WellnessOutput
from app.domain.wellness import evaluate

router = APIRouter(tags=["wellness"])


@router.post(
    "/internal/v1/wellness/evaluate",
    response_model=WellnessOutput,
    response_model_by_alias=True,
)
def evaluate_wellness(payload: WellnessInput) -> WellnessOutput:
    """Compute a deterministic, non-medical wellness notification priority."""
    return evaluate(payload)
