package com.hq.backend.notification;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.notification.dto.NotificationRespondRequest;
import com.hq.backend.notification.dto.NotificationResponse;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;
    private final PlanRevisionRepository planRevisionRepository;
    private final EventRepository eventRepository;
    private final com.hq.backend.wellness.WellnessEventSchedulerService wellnessEventSchedulerService;

    public NotificationQueryService(NotificationRepository notificationRepository,
                                    PlanRevisionRepository planRevisionRepository,
                                    EventRepository eventRepository,
                                    com.hq.backend.wellness.WellnessEventSchedulerService wellnessEventSchedulerService) {
        this.notificationRepository = notificationRepository;
        this.planRevisionRepository = planRevisionRepository;
        this.eventRepository = eventRepository;
        this.wellnessEventSchedulerService = wellnessEventSchedulerService;
    }

    /** 오늘(KST 기준) 예정·발송된 알림 조회 — 본인 일정만 */
    @Transactional(readOnly = true)
    public List<NotificationResponse> getTodayNotifications(UUID userId) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Instant startOfDay = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Instant endOfDay = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();

        // 사용자의 활성 계획 plan_id 목록을 구한 뒤 해당 알림만 반환
        List<UUID> userEventIds = eventRepository.findByUserId(userId)
                .stream()
                .map(Event::getEventId)
                .toList();

        if (userEventIds.isEmpty()) {
            return List.of();
        }

        List<UUID> userPlanIds = userEventIds.stream()
                .flatMap(eventId -> planRevisionRepository.findByEventIdOrderByRevisionNoDesc(eventId).stream())
                .filter(pr -> "active".equals(pr.getPlanStatus()))
                .map(PlanRevision::getPlanId)
                .toList();

        return notificationRepository.findAll().stream()
                .filter(n -> userPlanIds.contains(n.getPlanId()))
                .filter(n -> !n.getScheduledAt().isBefore(startOfDay) && n.getScheduledAt().isBefore(endOfDay))
                .map(this::toResponse)
                .toList();
    }

    /** 알림 응답 처리 (원탭 액션) */
    @Transactional
    public void respondToNotification(UUID userId, UUID notificationId, NotificationRespondRequest request) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "알림을 찾을 수 없습니다"));

        // 본인 소유 검증: notification → plan → event → userId
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
        }
        notification.setDeliveryStatus("delivered");
    }

    private NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(
                n.getNotificationId(),
                n.getPlanId(),
                n.getNotificationCategory(),
                n.getNotificationType(),
                n.getScheduledAt(),
                n.getSentAt(),
                n.getDeliveryStatus(),
                n.getBodyMasked(),
                n.getTriggerReason()
        );
    }
}
