package com.hq.backend.wellness;

import com.hq.backend.event.EventRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Generates the previous KST day's idempotent DWL cards after the day has closed. */
@Component
public class DailyWellnessSummaryScheduler {
    private static final Logger log = LoggerFactory.getLogger(DailyWellnessSummaryScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DailySummaryService dailySummaryService;
    private final EventRepository eventRepository;

    public DailyWellnessSummaryScheduler(DailySummaryService dailySummaryService, EventRepository eventRepository) {
        this.dailySummaryService = dailySummaryService;
        this.eventRepository = eventRepository;
    }

    @Scheduled(cron = "${wellness.daily-summary.cron:0 5 0 * * *}", zone = "Asia/Seoul")
    public void generatePreviousDay() {
        generateFor(LocalDate.now(KST).minusDays(1));
    }

    void generateFor(LocalDate date) {
        var start = date.atStartOfDay(KST).toInstant();
        var end = date.plusDays(1).atStartOfDay(KST).toInstant();
        for (UUID userId : eventRepository.findDistinctUserIdsByStartsAtBetween(start, end)) {
            try {
                dailySummaryService.generateIfAbsent(userId, date);
            } catch (RuntimeException e) {
                log.warn("[DailyWellnessSummary] user_id={}, date={} generation skipped: {}", userId, date, e.getMessage());
            }
        }
    }
}
