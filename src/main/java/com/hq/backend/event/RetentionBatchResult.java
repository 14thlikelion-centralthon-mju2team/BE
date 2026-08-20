package com.hq.backend.event;

/** Aggregate outcome of one fully drained retention pass. */
public record RetentionBatchResult(int processed, boolean hasMore) {

    public RetentionBatchResult {
        if (processed < 0) {
            throw new IllegalArgumentException("processed must not be negative");
        }
    }
}
