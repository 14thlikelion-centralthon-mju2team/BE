package com.hq.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class EventClassificationReviewRepositoryTest {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EventClassificationReviewRepository reviewRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void event당_미응답_review은_하나만_저장된다() {
        Event event = saveEvent();
        reviewRepository.saveAndFlush(pendingReview(event.getEventId()));

        assertThatThrownBy(() -> reviewRepository.saveAndFlush(pendingReview(event.getEventId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void provenance은_저장후_조회에도_보존된다() {
        Event event = saveEvent();
        UUID reviewId = UUID.randomUUID();
        Instant askedAt = Instant.parse("2026-08-20T12:00:00Z");
        jdbcTemplate.update("""
                insert into event_classification_review (
                    review_id, event_id, question_type, model_version, asked_at, title_purged_at,
                    provider, classifier_version, prompt_version, schema_version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                reviewId, event.getEventId(), "is_online", "gpt-5-mini-2026-08-07", Timestamp.from(askedAt),
                Timestamp.from(askedAt),
                "openai", "calendar-classifier-v1", "calendar-review-v1", "2026-08-20");

        EventClassificationReview saved = reviewRepository.findById(reviewId).orElseThrow();

        assertThat(saved).extracting(
                        "provider", "modelVersion", "classifierVersion", "promptVersion", "schemaVersion")
                .containsExactly(
                        "openai", "gpt-5-mini-2026-08-07", "calendar-classifier-v1",
                        "calendar-review-v1", "2026-08-20");
    }

    private Event saveEvent() {
        Instant now = Instant.parse("2026-08-20T12:00:00Z");
        User user = userRepository.saveAndFlush(User.builder()
                .email("review-" + UUID.randomUUID() + "@example.com")
                .nickname("review-" + UUID.randomUUID().toString().substring(0, 8))
                .timezone("Asia/Seoul")
                .createdAt(now)
                .accountStatus("active")
                .build());
        return eventRepository.saveAndFlush(Event.builder()
                .userId(user.getUserId())
                .sourceType("internal")
                .startsAt(now)
                .isAllDay(false)
                .locationState("not_required")
                .autoManageExcluded(false)
                .excludedFromLearning(false)
                .status("planned")
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private EventClassificationReview pendingReview(UUID eventId) {
        return EventClassificationReview.builder()
                .eventId(eventId)
                .questionType("is_online")
                .askedAt(Instant.parse("2026-08-20T12:00:00Z"))
                .titlePurgedAt(Instant.parse("2026-08-20T12:00:00Z"))
                .build();
    }
}
