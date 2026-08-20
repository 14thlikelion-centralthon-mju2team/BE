package com.hq.backend.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationDispatchServiceTest {

    @Mock private NotificationDispatchState dispatchState;
    @Mock private FcmSender fcmSender;

    @Test
    void 준비된_scheduled_notification을_FCM으로_보내고_결과를_기록한다() {
        UUID notificationId = UUID.randomUUID();
        NotificationDispatchState.DispatchCommand command = command(notificationId, List.of("token-1"));
        when(dispatchState.prepare(notificationId)).thenReturn(Optional.of(command));
        when(fcmSender.send(any(), any(), any(), any(), any())).thenReturn(1);
        when(dispatchState.complete(eq(command), eq(1), any(Instant.class))).thenReturn(true);

        service().dispatchScheduledNotification(notificationId);

        verify(fcmSender).send(
                eq(List.of("token-1")), eq("Ensom"), eq(command.bodyMasked()),
                eq(command.eventId() + ":" + command.notificationType()), any());
        verify(dispatchState).complete(eq(command), eq(1), any(Instant.class));
    }

    @Test
    void 취소되었거나_이미_처리된_notification은_FCM을_호출하지_않는다() {
        UUID notificationId = UUID.randomUUID();
        when(dispatchState.prepare(notificationId)).thenReturn(Optional.empty());

        service().dispatchScheduledNotification(notificationId);

        verify(fcmSender, never()).send(any(), any(), any(), any(), any());
        verify(dispatchState, never()).complete(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void 등록된_기기가_없으면_failed로_기록하고_FCM을_호출하지_않는다() {
        UUID notificationId = UUID.randomUUID();
        NotificationDispatchState.DispatchCommand command = command(notificationId, List.of());
        when(dispatchState.prepare(notificationId)).thenReturn(Optional.of(command));

        service().dispatchScheduledNotification(notificationId);

        verify(fcmSender, never()).send(any(), any(), any(), any(), any());
        verify(dispatchState).complete(eq(command), eq(0), any(Instant.class));
    }

    private NotificationDispatchService service() {
        return new NotificationDispatchService(dispatchState, fcmSender);
    }

    private NotificationDispatchState.DispatchCommand command(UUID notificationId, List<String> tokens) {
        return new NotificationDispatchState.DispatchCommand(
                notificationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "critical",
                "준비 알림",
                tokens);
    }
}
