package com.hq.backend.notification;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.notification.dto.NotificationRespondRequest;
import com.hq.backend.notification.dto.NotificationResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M2 API — /notifications
 * PRD §9 배치: GET /notifications/today, POST /notifications/{id}/respond
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationQueryService queryService;

    public NotificationController(NotificationQueryService queryService) {
        this.queryService = queryService;
    }

    /** 오늘 예정된·발송된 알림 목록 */
    @GetMapping("/today")
    public ResponseEntity<List<NotificationResponse>> getToday(@CurrentUserId UUID userId) {
        return ResponseEntity.ok(queryService.getTodayNotifications(userId));
    }

    /** 알림 응답 (원탭 액션: 준비 시작, 미루기 등) */
    @PostMapping("/{notificationId}/respond")
    public ResponseEntity<Void> respond(@CurrentUserId UUID userId,
                                        @PathVariable UUID notificationId,
                                        @RequestBody NotificationRespondRequest request) {
        queryService.respondToNotification(userId, notificationId, request);
        return ResponseEntity.ok().build();
    }
}
