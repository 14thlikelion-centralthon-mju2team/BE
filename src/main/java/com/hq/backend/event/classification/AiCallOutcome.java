package com.hq.backend.event.classification;

public enum AiCallOutcome {
    SUCCESS,
    TIMEOUT,
    HTTP_4XX,
    HTTP_5XX,
    REFUSAL,
    INCOMPLETE,
    INVALID_SCHEMA,
    SKIPPED_CONSENT,
    SKIPPED_ROLLOUT,
    SKIPPED_BUDGET,
    SKIPPED_BUSY
}
