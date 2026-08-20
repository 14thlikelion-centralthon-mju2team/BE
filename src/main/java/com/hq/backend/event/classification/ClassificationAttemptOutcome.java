package com.hq.backend.event.classification;

public enum ClassificationAttemptOutcome {
    REVIEW_CREATED(true),
    REVIEW_DUPLICATE(true),
    REVIEW_STALE(true),
    PROVIDER_EMPTY(true),
    SKIPPED_CONSENT(false),
    SKIPPED_ROLLOUT(false),
    SKIPPED_DISABLED(false),
    SKIPPED_INVALID_INPUT(false),
    SKIPPED_BUDGET(false),
    SKIPPED_BUSY(false);

    private final boolean providerCalled;

    ClassificationAttemptOutcome(boolean providerCalled) {
        this.providerCalled = providerCalled;
    }

    public boolean providerCalled() {
        return providerCalled;
    }
}
