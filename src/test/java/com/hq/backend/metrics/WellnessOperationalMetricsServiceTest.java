package com.hq.backend.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.backend.notification.Notification;
import com.hq.backend.notification.NotificationRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.wellness.DailyWellnessSummary;
import com.hq.backend.wellness.DailyWellnessSummaryRepository;
import com.hq.backend.wellness.WellnessEventSchedule;
import com.hq.backend.wellness.WellnessEventScheduleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WellnessOperationalMetricsServiceTest {
    @Test
    void snapshot_counts_only_daily_operational_states() {
        NotificationRepository notifications = Mockito.mock(NotificationRepository.class);
        WellnessEventScheduleRepository schedules = Mockito.mock(WellnessEventScheduleRepository.class);
        PlanRevisionRepository plans = Mockito.mock(PlanRevisionRepository.class);
        DailyWellnessSummaryRepository summaries = Mockito.mock(DailyWellnessSummaryRepository.class);
        Instant inDay = Instant.parse("2026-08-19T16:00:00Z"); // KST 2026-08-20 01:00
        when(notifications.findAll()).thenReturn(List.of(
                Notification.builder().notificationCategory("wellness").deliveryStatus("sent").scheduledAt(inDay).build(),
                Notification.builder().notificationCategory("time").deliveryStatus("sent").scheduledAt(inDay).build()));
        when(schedules.findAll()).thenReturn(List.of(
                WellnessEventSchedule.builder().scheduledAt(inDay).responseAction("stop_today").build()));
        when(plans.findAll()).thenReturn(List.of(
                PlanRevision.builder().createdAt(inDay).degraded("[\"env_unavailable\",\"config_fallback\"]").build()));
        when(summaries.findAll()).thenReturn(List.of(
                DailyWellnessSummary.builder().summaryDate(LocalDate.of(2026, 8, 20)).isViewed(true).build()));

        WellnessOperationalMetrics result = new WellnessOperationalMetricsService(
                notifications, schedules, plans, summaries, new ObjectMapper()).snapshot(LocalDate.of(2026, 8, 20));

        assertThat(result.wellnessNotificationsSent()).isEqualTo(1);
        assertThat(result.responseActionCounts()).containsEntry("stop_today", 1);
        assertThat(result.fallbackReasonCounts()).containsEntry("env_unavailable", 1)
                .containsEntry("config_fallback", 1);
        assertThat(result.dailySummariesCreated()).isEqualTo(1);
        assertThat(result.dailySummariesViewed()).isEqualTo(1);
    }
}
