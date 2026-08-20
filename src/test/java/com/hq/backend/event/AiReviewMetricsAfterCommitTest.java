package com.hq.backend.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.hq.backend.event.dto.EventReviewRequest;
import com.hq.backend.event.dto.EventUpdateRequest;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class AiReviewMetricsAfterCommitTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Autowired private EventService eventService;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationReviewRepository reviewRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    void answer_and_patch_are_counted_only_after_their_transactions_commit() {
        Subject answered = subject();
        double onlineBefore = reviewCount("answered_online");
        transactionTemplate.executeWithoutResult(status -> eventService.answerReview(
                answered.userId(), answered.event().getEventId(),
                new EventReviewRequest(answered.review().getReviewId(), "is_online", "online")));
        assertThat(reviewCount("answered_online")).isEqualTo(onlineBefore + 1);

        Subject patched = subject();
        double patchedBefore = reviewCount("closed_by_user_patch");
        transactionTemplate.executeWithoutResult(status -> eventService.update(
                patched.userId(), patched.event().getEventId(),
                new EventUpdateRequest(null, null, LocationState.NOT_REQUIRED,
                        null, null, null, null, null, null, null)));
        assertThat(reviewCount("closed_by_user_patch")).isEqualTo(patchedBefore + 1);

        Subject rolledBack = subject();
        double rollbackBefore = reviewCount("answered_offline");
        transactionTemplate.executeWithoutResult(status -> {
            eventService.answerReview(rolledBack.userId(), rolledBack.event().getEventId(),
                    new EventReviewRequest(rolledBack.review().getReviewId(), "is_online", "offline"));
            status.setRollbackOnly();
        });
        assertThat(reviewCount("answered_offline")).isEqualTo(rollbackBefore);
    }

    private Subject subject() {
        User user = userRepository.saveAndFlush(User.builder()
                .email("metrics-" + UUID.randomUUID() + "@example.com")
                .nickname("metrics-" + UUID.randomUUID().toString().substring(0, 8))
                .timezone("Asia/Seoul").accountStatus("active").createdAt(NOW).build());
        Event event = eventRepository.saveAndFlush(Event.builder()
                .userId(user.getUserId()).sourceType("external").startsAt(NOW).endsAt(NOW.plusSeconds(3600))
                .isAllDay(false).locationState("undecided").autoManageExcluded(false).excludedFromLearning(false)
                .status("planned").createdAt(NOW).updatedAt(NOW).build());
        EventClassificationReview review = reviewRepository.saveAndFlush(EventClassificationReview.builder()
                .eventId(event.getEventId()).questionType("is_online").suggestedValue("online")
                .classificationConfidence(new BigDecimal("0.9400")).askedAt(NOW).titlePurgedAt(NOW)
                .provider("openai").modelVersion("gpt-4o-mini-2024-07-18")
                .classifierVersion("classifier-v1").promptVersion("prompt-v1").schemaVersion("schema-v1").build());
        return new Subject(user.getUserId(), event, review);
    }

    private double reviewCount(String outcome) {
        Counter counter = meterRegistry.find("ai_classification_reviews_total").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private record Subject(UUID userId, Event event, EventClassificationReview review) {
    }
}
