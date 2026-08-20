package com.hq.backend.notification;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.pushdevice.PushDevice;
import com.hq.backend.pushdevice.PushDeviceRepository;
import com.hq.backend.wellness.WellnessEventScheduleRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FCM I/O와 분리된 notification dispatch DB 상태 전이.
 * prepare와 complete는 각각 짧은 transaction만 사용한다.
 */
@Service
@RequiredArgsConstructor
public class NotificationDispatchState {

    private final NotificationRepository notificationRepository;
    private final PlanRevisionRepository planRevisionRepository;
    private final EventRepository eventRepository;
    private final PushDeviceRepository pushDeviceRepository;
    private final WellnessEventScheduleRepository wellnessEventScheduleRepository;

    @Transactional(readOnly = true)
    public Optional<DispatchCommand> prepare(UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .filter(candidate -> "scheduled".equals(candidate.getDeliveryStatus()))
                .orElse(null);
        if (notification == null) {
            return Optional.empty();
        }

        PlanRevision revision = planRevisionRepository.findById(notification.getPlanId())
                .orElse(null);
        if (revision == null) {
            return Optional.empty();
        }

        Event event = eventRepository.findById(revision.getEventId()).orElse(null);
        if (event == null) {
            return Optional.empty();
        }

        List<String> tokens = pushDeviceRepository.findByUserIdAndTokenStatus(event.getUserId(), "active")
                .stream()
                .map(PushDevice::getCurrentToken)
                .toList();

        return Optional.of(new DispatchCommand(
                notification.getNotificationId(),
                notification.getPlanId(),
                event.getEventId(),
                notification.getNotificationType(),
                notification.getBodyMasked(),
                tokens));
    }

    @Transactional
    public boolean complete(DispatchCommand command, int sent, Instant sentAt) {
        Notification notification = notificationRepository.findById(command.notificationId())
                .filter(candidate -> "scheduled".equals(candidate.getDeliveryStatus()))
                .orElse(null);
        if (notification == null) {
            return false;
        }

        notification.setDeliveryStatus(sent > 0 ? "sent" : "failed");
        notification.setSentAt(sentAt);
        if (sent > 0) {
            wellnessEventScheduleRepository.findByNotificationId(notification.getNotificationId())
                    .ifPresent(schedule -> schedule.setSentAt(sentAt));
        }
        return true;
    }

    public record DispatchCommand(
            UUID notificationId,
            UUID planId,
            UUID eventId,
            String notificationType,
            String bodyMasked,
            List<String> tokens) {
    }
}
