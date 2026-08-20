package com.hq.backend.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.hq.backend.event.dto.EventReviewRequest;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class EventClassificationReviewRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Autowired private EventClassificationReviewRetentionService retentionService;
    @Autowired private EventClassificationReviewRetentionBatchWriter batchWriter;
    @Autowired private EventClassificationReviewRepository reviewRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EventService eventService;

    @BeforeEach
    void clearReviewsFromPreviousNonTransactionalRetentionRuns() {
        jdbcTemplate.update("delete from event_classification_review");
        entityManager.clear();
    }

    @AfterEach
    void clearReviewsCreatedByRetentionRuns() {
        jdbcTemplate.update("delete from event_classification_review");
        entityManager.clear();
    }

    @Test
    void title_snapshot은_24시간_cutoff_이전과_같은_행에서만_user_answer_변경없이_purge된다() {
        Event oldEvent = saveEvent();
        Event equalEvent = saveEvent();
        Event freshEvent = saveEvent();
        EventClassificationReview old = saveTitleReview(oldEvent.getEventId(), NOW.minus(24, ChronoUnit.HOURS));
        EventClassificationReview equal = saveTitleReview(equalEvent.getEventId(), NOW.minus(24, ChronoUnit.HOURS));
        EventClassificationReview fresh = saveTitleReview(freshEvent.getEventId(), NOW.minus(24, ChronoUnit.HOURS).plusMillis(1));

        assertThat(batchWriter.purgeBatch(NOW.minus(24, ChronoUnit.HOURS), NOW, 500)).isEqualTo(2);

        entityManager.clear();
        assertThat(reviewRepository.findById(old.getReviewId()).orElseThrow().getTitleSnapshot()).isNull();
        assertThat(reviewRepository.findById(equal.getReviewId()).orElseThrow().getTitlePurgedAt()).isEqualTo(NOW);
        EventClassificationReview untouched = reviewRepository.findById(fresh.getReviewId()).orElseThrow();
        assertThat(untouched.getTitleSnapshot()).isEqualTo("sensitive calendar title");
        assertThat(untouched.getUserAnswer()).isNull();
    }

    @Test
    void delete는_pending과_answered를_asked_at_review_id_순서로_최대_batchSize만_삭제하고_cutoff_동일_행은_남긴다() {
        Event event = saveEvent();
        Event pendingEvent = saveEvent();
        EventClassificationReview oldest = savePurgedReview(event.getEventId(), NOW.minus(91, ChronoUnit.DAYS));
        EventClassificationReview middle = savePurgedReview(event.getEventId(), NOW.minus(90, ChronoUnit.DAYS).minusSeconds(2));
        EventClassificationReview newestOld = savePurgedReview(event.getEventId(), NOW.minus(90, ChronoUnit.DAYS).minusSeconds(1));
        EventClassificationReview pending = savePendingPurgedReview(pendingEvent.getEventId(), NOW.minus(90, ChronoUnit.DAYS).minusSeconds(3));
        EventClassificationReview atCutoff = savePurgedReview(event.getEventId(), NOW.minus(90, ChronoUnit.DAYS));

        assertThat(batchWriter.deleteBatch(NOW.minus(90, ChronoUnit.DAYS), 2)).isEqualTo(2);

        assertThat(reviewRepository.existsById(pending.getReviewId())).isFalse();
        assertThat(reviewRepository.existsById(oldest.getReviewId())).isFalse();
        assertThat(reviewRepository.existsById(middle.getReviewId())).isTrue();
        assertThat(reviewRepository.existsById(newestOld.getReviewId())).isTrue();
        assertThat(reviewRepository.existsById(atCutoff.getReviewId())).isTrue();
    }

    @Test
    void 같은_askedAt에서는_reviewId_tie_breaker가_첫_삭제_batch를_결정한다() {
        Event event = saveEvent();
        Instant askedAt = NOW.minus(91, ChronoUnit.DAYS);
        EventClassificationReview first = savePurgedReview(event.getEventId(), askedAt);
        EventClassificationReview second = savePurgedReview(event.getEventId(), askedAt);
        UUID expectedFirst = jdbcTemplate.queryForObject("""
                select review_id from event_classification_review
                where review_id in (?, ?)
                order by asked_at, review_id
                limit 1
                """, (resultSet, rowNum) -> resultSet.getObject(1, UUID.class),
                first.getReviewId(), second.getReviewId());
        UUID expectedSecond = expectedFirst.equals(first.getReviewId()) ? second.getReviewId() : first.getReviewId();

        assertThat(batchWriter.deleteBatch(NOW.minus(90, ChronoUnit.DAYS), 1)).isEqualTo(1);

        assertThat(reviewRepository.existsById(expectedFirst)).isFalse();
        assertThat(reviewRepository.existsById(expectedSecond)).isTrue();
    }

    @Test
    void service는_501개_삭제대상을_500개_CTE_batch로_끝까지_드레인한다() {
        Event event = saveEvent();
        List<UUID> reviewIds = insertExpiredAnsweredReviews(event.getEventId(), 501);

        RetentionBatchResult result = retentionService.deleteExpired(NOW.minus(90, ChronoUnit.DAYS), 500);

        assertThat(result).isEqualTo(new RetentionBatchResult(501, false));
        assertThat(reviewIds).allSatisfy(id -> assertThat(reviewRepository.existsById(id)).isFalse());
    }

    @Test
    void service는_정확히_500개_만료_review도_후속_빈_batch까지_확인해_남김없이_삭제한다() {
        Event event = saveEvent();
        List<UUID> reviewIds = insertExpiredAnsweredReviews(event.getEventId(), 500);

        RetentionBatchResult result = retentionService.deleteExpired(NOW.minus(90, ChronoUnit.DAYS), 500);

        assertThat(result).isEqualTo(new RetentionBatchResult(500, false));
        assertThat(reviewIds).allSatisfy(id -> assertThat(reviewRepository.existsById(id)).isFalse());
    }

    @Test
    void answer가_review_lock을_기다리는동안_purge는_skip_locked로_건너뛰고_답변필드를_유실하지_않는다() throws Exception {
        Event event = saveEvent();
        EventClassificationReview review = saveTitleReview(event.getEventId(), NOW.minus(25, ChronoUnit.HOURS));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                reviewRepository.findByReviewIdAndEventIdForUpdate(review.getReviewId(), event.getEventId()).orElseThrow();
                locked.countDown();
                await(release);
            }));
            await(locked);

            Future<?> answer = executor.submit(() -> eventService.answerReview(
                    event.getUserId(), event.getEventId(),
                    new EventReviewRequest(review.getReviewId(), "is_online", "online")));

            assertThat(batchWriter.purgeBatch(NOW.minus(24, ChronoUnit.HOURS), NOW, 500)).isZero();
            release.countDown();
            holder.get(5, java.util.concurrent.TimeUnit.SECONDS);
            answer.get(5, java.util.concurrent.TimeUnit.SECONDS);

            entityManager.clear();
            EventClassificationReview saved = reviewRepository.findById(review.getReviewId()).orElseThrow();
            assertThat(saved.getAnsweredAt()).isNotNull();
            assertThat(saved.getUserAnswer()).isEqualTo("online");
            assertThat(saved.getTitleSnapshot()).isNull();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void delete는_잠긴_만료_review을_skip_locked로_건너뛰고_다음실행에서_삭제한다() throws Exception {
        Event event = saveEvent();
        EventClassificationReview review = savePurgedReview(event.getEventId(), NOW.minus(91, ChronoUnit.DAYS));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                reviewRepository.findByReviewIdAndEventIdForUpdate(review.getReviewId(), event.getEventId()).orElseThrow();
                locked.countDown();
                await(release);
            }));
            await(locked);

            assertThat(batchWriter.deleteBatch(NOW.minus(90, ChronoUnit.DAYS), 500)).isZero();
            assertThat(reviewRepository.existsById(review.getReviewId())).isTrue();
            release.countDown();
            holder.get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(batchWriter.deleteBatch(NOW.minus(90, ChronoUnit.DAYS), 500)).isEqualTo(1);
            assertThat(reviewRepository.existsById(review.getReviewId())).isFalse();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private Event saveEvent() {
        User user = userRepository.saveAndFlush(User.builder()
                .email("retention-" + UUID.randomUUID() + "@example.com")
                .nickname("retention-" + UUID.randomUUID().toString().substring(0, 8))
                .timezone("Asia/Seoul").accountStatus("active").createdAt(NOW).build());
        return eventRepository.saveAndFlush(Event.builder()
                .userId(user.getUserId()).sourceType("external").startsAt(NOW.plusSeconds(3600))
                .isAllDay(false).locationState("undecided").autoManageExcluded(false)
                .excludedFromLearning(false).status("planned").createdAt(NOW).updatedAt(NOW).build());
    }

    private EventClassificationReview saveTitleReview(UUID eventId, Instant askedAt) {
        return reviewRepository.saveAndFlush(EventClassificationReview.builder()
                .eventId(eventId).titleSnapshot("sensitive calendar title").questionType("is_online")
                .suggestedValue("online").classificationConfidence(new BigDecimal("0.9400"))
                .askedAt(askedAt).build());
    }

    private EventClassificationReview savePurgedReview(UUID eventId, Instant askedAt) {
        EventClassificationReview review = reviewRepository.saveAndFlush(EventClassificationReview.builder()
                .eventId(eventId).questionType("is_online").suggestedValue("online")
                .classificationConfidence(new BigDecimal("0.9400")).askedAt(askedAt).titlePurgedAt(askedAt)
                .build());
        review.setUserAnswer("online");
        review.setAnsweredAt(askedAt);
        return reviewRepository.saveAndFlush(review);
    }

    private EventClassificationReview savePendingPurgedReview(UUID eventId, Instant askedAt) {
        return reviewRepository.saveAndFlush(EventClassificationReview.builder()
                .eventId(eventId).questionType("is_online").suggestedValue("online")
                .classificationConfidence(new BigDecimal("0.9400")).askedAt(askedAt).titlePurgedAt(askedAt)
                .build());
    }

    private List<UUID> insertExpiredAnsweredReviews(UUID eventId, int count) {
        String sql = """
                insert into event_classification_review (
                    review_id, event_id, question_type, suggested_value, classification_confidence,
                    asked_at, answered_at, title_purged_at
                ) values (?, ?, 'is_online', 'online', 0.9400, ?, ?, ?)
                """;
        List<UUID> reviewIds = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            UUID reviewId = UUID.randomUUID();
            Instant askedAt = NOW.minus(91, ChronoUnit.DAYS).plusSeconds(index);
            jdbcTemplate.update(sql, reviewId, eventId, Timestamp.from(askedAt), Timestamp.from(askedAt), Timestamp.from(askedAt));
            reviewIds.add(reviewId);
        }
        return reviewIds;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("retention 동시성 latch 대기 시간이 5초를 초과했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
