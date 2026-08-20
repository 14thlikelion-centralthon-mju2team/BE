package com.hq.backend.event.classification;

public enum AiReviewOutcome {
    CREATED,
    DUPLICATE,
    STALE,
    ANSWERED_ONLINE,
    ANSWERED_OFFLINE,
    CLOSED_BY_USER_PATCH
}
