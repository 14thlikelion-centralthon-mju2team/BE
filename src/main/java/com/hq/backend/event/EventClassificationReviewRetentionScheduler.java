package com.hq.backend.event;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EventClassificationReviewRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventClassificationReviewRetentionScheduler.class);
    private static final int BATCH_SIZE = 500;

    private final EventClassificationReviewRetentionService retentionService;
    private final Clock clock;

    public EventClassificationReviewRetentionScheduler(EventClassificationReviewRetentionService retentionService, Clock clock) {
        this.retentionService = retentionService;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent ignored) {
        purgeTitles();
    }

    @Scheduled(fixedDelayString = "${openai.classification.retention.purge-delay-ms:300000}")
    public void purgeTitles() {
        try {
            retentionService.purgeTitles(Instant.now(clock).minus(24, ChronoUnit.HOURS), BATCH_SIZE);
        } catch (RuntimeException exception) {
            log.warn("AI review title retention pass failed");
        }
    }

    @Scheduled(cron = "${openai.classification.retention.delete-cron:0 30 3 * * *}", zone = "UTC")
    public void deleteExpired() {
        try {
            retentionService.deleteExpired(Instant.now(clock).minus(90, ChronoUnit.DAYS), BATCH_SIZE);
        } catch (RuntimeException exception) {
            log.warn("AI review deletion retention pass failed");
        }
    }
}
