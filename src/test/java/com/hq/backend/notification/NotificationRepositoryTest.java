package com.hq.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class NotificationRepositoryTest {

    @Autowired private NotificationRepository notificationRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private PlanRevisionRepository planRevisionRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void 같은_dedup_key를_동시에_예약해도_두번째는_예외대신_0을_반환한다() {
        Instant now = Instant.now();
        User user = userRepository.save(User.builder()
                .email("notification-" + UUID.randomUUID() + "@example.com")
                .nickname("notification" + UUID.randomUUID())
                .timezone("Asia/Seoul")
                .accountStatus("active")
                .createdAt(now)
                .build());
        Event event = eventRepository.save(Event.builder()
                .userId(user.getUserId())
                .sourceType("internal")
                .startsAt(now.plusSeconds(3600))
                .isAllDay(false)
                .locationState("not_required")
                .autoManageExcluded(false)
                .status("planned")
                .createdAt(now)
                .build());
        PlanRevision plan = planRevisionRepository.save(PlanRevision.builder()
                .eventId(event.getEventId())
                .revisionNo(1)
                .prepStartAt(now.plusSeconds(1800))
                .recommendedDepartAt(now.plusSeconds(2400))
                .targetArriveAt(now.plusSeconds(3600))
                .estimatedPrepMinutes(0)
                .extraPrepMinutes(0)
                .personalRoutineMinutes(0)
                .travelMinutes(0)
                .trafficBufferMinutes(0)
                .arrivalBufferMinutes(0)
                .feasible(true)
                .reasons("[]")
                .degraded("[]")
                .predictionConfidence("high")
                .planStatus("active")
                .calcVersion("test")
                .createdAt(now)
                .build());
        String dedupKey = UUID.randomUUID().toString();

        int first = notificationRepository.insertIfAbsent(
                plan.getPlanId(), "time", "relaxed", now, "준비 알림", "test", dedupKey);
        int second = notificationRepository.insertIfAbsent(
                plan.getPlanId(), "time", "relaxed", now, "준비 알림", "test", dedupKey);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(notificationRepository.findByDedupKey(dedupKey)).isPresent();
    }
}
