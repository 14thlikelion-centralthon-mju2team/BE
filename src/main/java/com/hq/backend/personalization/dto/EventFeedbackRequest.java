package com.hq.backend.personalization.dto;

import com.hq.backend.personalization.ArrivalResult;
import com.hq.backend.personalization.PrepTimingAssessment;
import com.hq.backend.personalization.RushAssessment;
import jakarta.validation.constraints.NotNull;

public record EventFeedbackRequest(
        @NotNull PrepTimingAssessment prepTimingAssessment,
        ArrivalResult arrivalResult,
        RushAssessment rushAssessment
) {
}
