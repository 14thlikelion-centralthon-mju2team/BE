package com.hq.backend.metrics;

import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WellnessOperationalMetricsScheduler {
    private static final Logger log = LoggerFactory.getLogger(WellnessOperationalMetricsScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final WellnessOperationalMetricsService metricsService;

    public WellnessOperationalMetricsScheduler(WellnessOperationalMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @Scheduled(cron = "${wellness.metrics.cron:0 15 0 * * *}", zone = "Asia/Seoul")
    public void logPreviousDaySnapshot() {
        WellnessOperationalMetrics metrics = metricsService.snapshot(LocalDate.now(KST).minusDays(1));
        log.info("[WellnessMetrics] date={}, scheduled={}, sent={}, failed={}, cancelled={}, responses={}, fallbacks={}, summaries={}, viewed={}",
                metrics.date(), metrics.wellnessNotificationsScheduled(), metrics.wellnessNotificationsSent(),
                metrics.wellnessNotificationsFailed(), metrics.wellnessNotificationsCancelled(),
                metrics.responseActionCounts(), metrics.fallbackReasonCounts(), metrics.dailySummariesCreated(),
                metrics.dailySummariesViewed());
    }
}
