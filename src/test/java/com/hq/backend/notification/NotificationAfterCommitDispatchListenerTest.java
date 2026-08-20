package com.hq.backend.notification;

import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationAfterCommitDispatchListenerTest {

    @Mock private NotificationDispatchService notificationDispatchService;

    @Test
    void commit_후_이벤트의_notification을_dispatch한다() {
        UUID notificationId = UUID.randomUUID();

        new NotificationAfterCommitDispatchListener(notificationDispatchService)
                .dispatch(new NotificationDueEvent(notificationId));

        verify(notificationDispatchService).dispatchScheduledNotification(notificationId);
    }
}
