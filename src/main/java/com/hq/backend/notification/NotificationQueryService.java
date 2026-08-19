package com.hq.backend.notification;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.ActionSource;
import com.hq.backend.event.ActionType;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventActionLogRepository;
import com.hq.backend.event.EventRepository;
import com.hq.backend.notification.dto.NotificationRespondRequest;
import com.hq.backend.notification.dto.NotificationResponse;
import com.hq.backend.plan.PlanActionService;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.plan.dto.ActionBatchRequest;
import com.hq.backend.wellness.WellnessEventScheduleRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationQueryService {
    private final NotificationRepository notificationRepository;
    private final PlanRevisionRepository planRevisionRepository;
    private final EventRepository eventRepository;
    private final PlanActionService planActionService;
    private final com.hq.backend.wellness.WellnessEventSchedulerService wellnessEventSchedulerService;
    private final EventActionLogRepository eventActionLogRepository;
    private final WellnessEventScheduleRepository wellnessEventScheduleRepository;

    public NotificationQueryService(NotificationRepository notificationRepository,
                                    PlanRevisionRepository planRevisionRepository,
                                    EventRepository eventRepository,
                                    PlanActionService planActionService,
                                    com.hq.backend.wellness.WellnessEventSchedulerService wellnessEventSchedulerService,
                                    EventActionLogRepository eventActionLogRepository,
                                    WellnessEventScheduleRepository wellnessEventScheduleRepository) {
        this.notificationRepository = notificationRepository;
        this.planRevisionRepository = planRevisionRepository;
        this.eventRepository = eventRepository;
        this.planActionService = planActionService;
        this.wellnessEventSchedulerService = wellnessEventSchedulerService;
        this.eventActionLogRepository = eventActionLogRepository;
        this.wellnessEventScheduleRepository = wellnessEventScheduleRepository;
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getTodayNotifications(UUID userId) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Instant start = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        List<UUID> eventIds = eventRepository.findByUserId(userId).stream().map(Event::getEventId).toList();
        if (eventIds.isEmpty()) return List.of();
        List<UUID> planIds = eventIds.stream()
                .flatMap(id -> planRevisionRepository.findByEventIdOrderByRevisionNoDesc(id).stream())
                .filter(plan -> "active".equals(plan.getPlanStatus())).map(PlanRevision::getPlanId).toList();
        return notificationRepository.findAll().stream()
                .filter(notification -> planIds.contains(notification.getPlanId()))
                .filter(notification -> !"cancelled".equals(notification.getDeliveryStatus()))
                .filter(notification -> !notification.getScheduledAt().isBefore(start) && notification.getScheduledAt().isBefore(end))
                .map(this::toResponse).toList();
    }

    @Transactional
    public void respondToNotification(UUID userId, UUID notificationId, NotificationRespondRequest request) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "알림을 찾을 수 없습니다"));
        PlanRevision plan = planRevisionRepository.findById(notification.getPlanId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "계획을 찾을 수 없습니다"));
        Event event = eventRepository.findById(plan.getEventId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "일정을 찾을 수 없습니다"));
        if (!event.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "알림을 찾을 수 없습니다");
        }
        if ("wellness".equals(notification.getNotificationCategory())) {
            try {
                wellnessEventSchedulerService.handleNotificationResponse(
                        notificationId, request.reaction(), userId);
            } catch (IllegalArgumentException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REACTION", exception.getMessage());
            }
        } else {
            ActionType action = actionFor(request.reaction());
            if (action != null) {
                String reaction = request.reaction().trim().toLowerCase(Locale.ROOT);
                UUID clientEventId = UUID.nameUUIDFromBytes(("notification:" + notificationId + ":" + reaction)
                        .getBytes(StandardCharsets.UTF_8));
                planActionService.submit(userId, plan.getPlanId(), new ActionBatchRequest(List.of(
                        new ActionBatchRequest.ActionItem(
                                action, ActionSource.USER, Instant.now(), clientEventId, notificationId, null))));
            }
        }
        notification.setDeliveryStatus("delivered");
    }

    private ActionType actionFor(String reaction) {
        return switch (reaction.trim().toLowerCase(Locale.ROOT)) {
            case "started" -> ActionType.PREP_STARTED;
            case "snoozed" -> ActionType.SNOOZED;
            case "departed" -> ActionType.DEPARTED;
            case "dismissed" -> null;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REACTION", "지원하지 않는 알림 반응입니다");
        };
    }

    private NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getNotificationId(),
                notification.getPlanId(),
                notification.getNotificationCategory(),
                notification.getNotificationType(),
                slotFor(notification.getNotificationType()),
                notification.getScheduledAt(),
                notification.getSentAt(),
                deliveryStatusFor(notification.getDeliveryStatus()),
                notification.getBodyMasked(),
                notification.getTriggerReason(),
                reactionFor(notification));
    }

    private String slotFor(String notificationType) {
        return switch (notificationType) {
            case "relaxed" -> "A";
            case "critical" -> "B";
            case "disruption" -> "C";
            case "wellness_event" -> "W";
            default -> throw new IllegalStateException("unsupported notification type: " + notificationType);
        };
    }

    private String deliveryStatusFor(String deliveryStatus) {
        return switch (deliveryStatus) {
            case "scheduled" -> "pending";
            case "sent", "delivered" -> "delivered";
            case "failed" -> "failed";
            default -> throw new IllegalStateException("unsupported delivery status: " + deliveryStatus);
        };
    }

    private String reactionFor(Notification notification) {
        if ("wellness".equals(notification.getNotificationCategory())) {
            return wellnessEventScheduleRepository.findByNotificationId(notification.getNotificationId())
                    .map(com.hq.backend.wellness.WellnessEventSchedule::getResponseAction)
                    .orElse(null);
        }
        return eventActionLogRepository
                .findFirstByNotificationIdOrderByReceivedAtDesc(notification.getNotificationId())
                .map(com.hq.backend.event.EventActionLog::getActionType)
                .orElse(null);
    }
}
