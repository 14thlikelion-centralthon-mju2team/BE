package com.hq.backend.personalization.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

// ai/plan-engine PersonalizationOutput 계약과 1:1 대응. candidates는 TR-05의 단일 조정과
// 별개로 EVENT_DELAY_REASON 복합 PK가 허용하는 복수 원인 기록에 사용한다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record PersonalizationEngineResponse(
        String cause, // prep_late | prep_overrun | depart_late | traffic | external | unknown
        String adjustedKnob, // prep_estimate | notification_lead | departure_lead | traffic_buffer | none
        Double previousValue,
        Double newValue,
        String adjustmentReason,
        boolean excludedFromLearning,
        String modelVersion,
        String contractVersion,
        Double causeConfidence,
        List<CauseCandidate> candidates,
        List<String> exclusionReasons,
        List<String> degraded
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CauseCandidate(String cause, Double confidence, Double signalMinutes) {
    }
}
