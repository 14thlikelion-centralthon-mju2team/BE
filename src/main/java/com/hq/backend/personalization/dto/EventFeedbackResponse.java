package com.hq.backend.personalization.dto;

import com.hq.backend.personalization.ArrivalResult;
import com.hq.backend.personalization.EventFeedback;
import com.hq.backend.personalization.PrepTimingAssessment;
import com.hq.backend.personalization.RushAssessment;
import java.util.UUID;

public record EventFeedbackResponse(
        UUID eventId,
        PrepTimingAssessment prepTimingAssessment,
        ArrivalResult arrivalResult,
        RushAssessment rushAssessment
) {

    public static EventFeedbackResponse from(EventFeedback feedback) {
        return new EventFeedbackResponse(
                feedback.getEventId(),
                PrepTimingAssessment.valueOf(feedback.getPrepTimingAssessment().toUpperCase()),
                feedback.getArrivalResult() != null
                        ? ArrivalResult.valueOf(feedback.getArrivalResult().toUpperCase()) : null,
                feedback.getRushAssessment() != null
                        ? RushAssessment.valueOf(feedback.getRushAssessment().toUpperCase()) : null);
    }
}
