package com.hq.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.dto.UpdateEventRequest;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// updateEvent()는 managed 엔티티를 먼저 setter로 바꾼 뒤 validateRange()에서 예외를 던진다
// (PlaceServiceTest류 Mockito 테스트는 진짜 트랜잭션이 없어서 이 케이스를 검증 못 한다) —
// @Transactional 메서드가 RuntimeException으로 롤백될 때 그 중간 수정이 실제로 DB에
// 반영 안 되는지 진짜 Postgres로 확인한다.
@SpringBootTest
class UserEventServiceIntegrationTest {

    @Autowired private UserEventService userEventService;
    @Autowired private UserEventRepository userEventRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void 잘못된_수정으로_실패하면_기존_값이_그대로_남는다() {
        String uniqueSuffix = UUID.randomUUID().toString();
        UUID userId = userRepository.save(User.builder()
                        .provider("email")
                        .providerUid("event-rollback-" + uniqueSuffix)
                        .email("event-rollback-" + uniqueSuffix + "@example.com")
                        .passwordHash("test-hash")
                        .nickname("event-rollback-tester")
                        .timezone("Asia/Seoul")
                        .createdAt(Instant.now())
                        .build())
                .getId();
        Instant startsAt = Instant.now();
        Instant endsAt = startsAt.plusSeconds(3600);
        UserEvent event = userEventRepository.save(UserEvent.builder()
                .userId(userId)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .createdAt(Instant.now())
                .build());

        var invalidRequest = new UpdateEventRequest(null, endsAt.plusSeconds(1), null, null); // startsAt을 endsAt 뒤로

        assertThatThrownBy(() -> userEventService.updateEvent(userId, event.getId(), invalidRequest))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_EVENT_RANGE");

        UserEvent reloaded = userEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStartsAt()).isEqualTo(startsAt);
        assertThat(reloaded.getEndsAt()).isEqualTo(endsAt);
    }
}
