package com.hq.backend.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.backend.notification.Notification;
import com.hq.backend.notification.NotificationRepository;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.wellness.DailyWellnessSummaryRepository;
import com.hq.backend.wellness.WellnessEventScheduleRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * M3 operational aggregation. This is intentionally aggregate-only: it never reads product-event
 * JSON payloads and never exposes user IDs, titles, coordinates, item labels, or rendered copy.
 */
@Service
public class WellnessOperationalMetricsService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final NotificationRepository notificationRepository;
    private final WellnessEventScheduleRepository scheduleRepository;
    private final PlanRevisionRepository planRevisionRepository;
    private final DailyWellnessSummaryRepository summaryRepository;
    private final ObjectMapper objectMapper;

    public WellnessOperationalMetricsService(NotificationRepository notificationRepository,
            WellnessEventScheduleRepository scheduleRepository, PlanRevisionRepository planRevisionRepository,
            DailyWellnessSummaryRepository summaryRepository, ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.scheduleRepository = scheduleRepository;
        this.planRevisionRepository = planRevisionRepository;
        this.summaryRepository = summaryRepository;
        this.objectMapper = objectMapper;
    }

    public WellnessOperationalMetrics snapshot(LocalDate date) {
        Map<String, Integer> delivery = new LinkedHashMap<>();
        for (Notification notification : notificationRepository.findAll()) {
            if ("wellness".equals(notification.getNotificationCategory())
                    && date.equals(notification.getScheduledAt().atZone(KST).toLocalDate())) {
                delivery.merge(notification.getDeliveryStatus(), 1, Integer::sum);
            }
        }

        Map<String, Integer> responses = new LinkedHashMap<>();
        for (var schedule : scheduleRepository.findAll()) {
            if (date.equals(schedule.getScheduledAt().atZone(KST).toLocalDate())
                    && schedule.getResponseAction() != null) {
                responses.merge(schedule.getResponseAction(), 1, Integer::sum);
            }
        }

        Map<String, Integer> fallbacks = new LinkedHashMap<>();
        planRevisionRepository.findAll().stream()
                .filter(plan -> date.equals(plan.getCreatedAt().atZone(KST).toLocalDate()))
                .flatMap(plan -> degradedCodes(plan.getDegraded()).stream())
                .forEach(code -> fallbacks.merge(code, 1, Integer::sum));

        var summaries = summaryRepository.findAll().stream()
                .filter(summary -> date.equals(summary.getSummaryDate()))
                .toList();
        return new WellnessOperationalMetrics(date,
                delivery.getOrDefault("scheduled", 0), delivery.getOrDefault("sent", 0),
                delivery.getOrDefault("failed", 0), delivery.getOrDefault("cancelled", 0),
                responses, fallbacks, summaries.size(),
                (int) summaries.stream().filter(summary -> summary.isViewed()).count());
    }

    private List<String> degradedCodes(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return objectMapper.readValue(raw, STRING_LIST).stream()
                    .filter(code -> code != null && code.matches("[a-z0-9_]+"))
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
