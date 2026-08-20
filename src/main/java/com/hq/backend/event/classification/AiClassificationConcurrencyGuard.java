package com.hq.backend.event.classification;

import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

@Component
public class AiClassificationConcurrencyGuard {

    private final Semaphore permits;

    public AiClassificationConcurrencyGuard(AiClassificationProperties properties) {
        this.permits = new Semaphore(properties.classification().maxConcurrency());
    }

    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }
}
