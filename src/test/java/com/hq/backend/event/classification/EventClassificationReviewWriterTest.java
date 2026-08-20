package com.hq.backend.event.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventClassificationReview;
import com.hq.backend.event.EventClassificationReviewRepository;
import com.hq.backend.event.EventRepository;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class EventClassificationReviewWriterTest {

    private static final Instant ASKED_AT = Instant.parse("2026-08-20T12:00:00Z");

    @Autowired private EventClassificationReviewWriter writer;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationReviewRepository reviewRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    void eligible_event_creates_a_purged_review_with_complete_provenance() {
        Event event = saveEvent("undecided", "planned", false, null);

        CreateReviewOutcome outcome = writer.createIfEligible(
                event.getEventId(), event.getRevision(), classificationResult(), ASKED_AT);

        assertThat(outcome).isEqualTo(CreateReviewOutcome.CREATED);
        EventClassificationReview review = reviewRepository
                .findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(event.getEventId()).orElseThrow();
        assertThat(review.getTitleSnapshot()).isNull();
        assertThat(review.getTitlePurgedAt()).isEqualTo(ASKED_AT);
        assertThat(review.getUserAnswer()).isNull();
        assertThat(review.getAnsweredAt()).isNull();
        assertThat(review).extracting("questionType", "suggestedValue", "modelVersion", "provider",
                        "classifierVersion", "promptVersion", "schemaVersion")
                .containsExactly("is_online", "online", "gpt-4o-mini-2024-08-06", "openai",
                        "classifier-v1", "prompt-v1", "schema-v1");
        assertThat(review.getClassificationConfidence()).isEqualByComparingTo("0.9400");
    }

    @Test
    void ineligible_event_or_existing_pending_review_never_creates_another_review() {
        Event stale = saveEvent("not_required", "planned", false, null);
        Event eligible = saveEvent("undecided", "planned", false, null);

        assertThat(writer.createIfEligible(stale.getEventId(), stale.getRevision(), classificationResult(), ASKED_AT))
                .isEqualTo(CreateReviewOutcome.STALE);
        assertThat(writer.createIfEligible(
                eligible.getEventId(), eligible.getRevision(), classificationResult(), ASKED_AT))
                .isEqualTo(CreateReviewOutcome.CREATED);
        assertThat(writer.createIfEligible(
                eligible.getEventId(), eligible.getRevision(), classificationResult(), ASKED_AT.plusSeconds(1)))
                .isEqualTo(CreateReviewOutcome.DUPLICATE);
        assertThat(reviewRepository.findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(stale.getEventId()))
                .isEmpty();
    }

    @Test
    void records_each_persisted_review_writer_outcome_once() {
        Event stale = saveEvent("not_required", "planned", false, null);
        Event eligible = saveEvent("undecided", "planned", false, null);
        double createdBefore = reviewCount("created");
        double duplicateBefore = reviewCount("duplicate");
        double staleBefore = reviewCount("stale");

        writer.createIfEligible(stale.getEventId(), stale.getRevision(), classificationResult(), ASKED_AT);
        writer.createIfEligible(eligible.getEventId(), eligible.getRevision(), classificationResult(), ASKED_AT);
        writer.createIfEligible(
                eligible.getEventId(), eligible.getRevision(), classificationResult(), ASKED_AT.plusSeconds(1));

        assertThat(reviewCount("stale")).isEqualTo(staleBefore + 1);
        assertThat(reviewCount("created")).isEqualTo(createdBefore + 1);
        assertThat(reviewCount("duplicate")).isEqualTo(duplicateBefore + 1);
    }

    @Test
    void failed_requires_new_writer_transaction_does_not_publish_a_review_metric() {
        Event event = saveEvent("undecided", "planned", false, null);
        double createdBefore = reviewCount("created");

        assertThatThrownBy(() -> writer.createIfEligible(event.getEventId(), event.getRevision(), new EventClassificationResult(
                null, "online", new BigDecimal("0.9400"), "openai", "gpt-4o-mini-2024-08-06",
                "classifier-v1", "prompt-v1", "schema-v1"), ASKED_AT))
                .isInstanceOf(RuntimeException.class);

        assertThat(reviewCount("created")).isEqualTo(createdBefore);
    }

    @Test
    void event_lock_rechecks_eligibility_after_a_concurrent_user_change() throws Exception {
        Event event = saveEvent("undecided", "planned", false, null);
        CountDownLatch eventLocked = new CountDownLatch(1);
        CountDownLatch commitUserChange = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> userChangeFuture = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                Event locked = eventRepository.findByIdForUpdate(event.getEventId()).orElseThrow();
                eventLocked.countDown();
                await(commitUserChange);
                locked.setMeetingUrl("https://meeting.example");
            }));
            await(eventLocked);
            Future<CreateReviewOutcome> writerFuture = executor.submit(() -> {
                await(eventLocked);
                return writer.createIfEligible(
                        event.getEventId(), event.getRevision(), classificationResult(), ASKED_AT);
            });
            commitUserChange.countDown();
            userChangeFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(writerFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(CreateReviewOutcome.STALE);
            assertThat(reviewRepository.findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(event.getEventId()))
                    .isEmpty();
        } finally {
            commitUserChange.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void event_changed_after_the_classification_snapshot_is_stale_even_when_it_remains_eligible() {
        Event event = saveEvent("undecided", "planned", false, null);
        Long classificationRevision = event.getRevision();
        event.setDisplayLabel("사용자가 바꿄 일정");
        Event changed = eventRepository.saveAndFlush(event);
        assertThat(changed.getRevision()).isNotEqualTo(classificationRevision);

        CreateReviewOutcome outcome = writer.createIfEligible(
                event.getEventId(), classificationRevision, classificationResult(), ASKED_AT);

        assertThat(outcome).isEqualTo(CreateReviewOutcome.STALE);
        assertThat(reviewRepository.findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(event.getEventId()))
                .isEmpty();
    }

    private Event saveEvent(String locationState, String status, boolean autoManageExcluded, String meetingUrl) {
        User user = userRepository.saveAndFlush(User.builder()
                .email("classification-" + UUID.randomUUID() + "@example.com")
                .nickname("classification-" + UUID.randomUUID().toString().substring(0, 8))
                .timezone("Asia/Seoul").accountStatus("active").createdAt(ASKED_AT).build());
        return eventRepository.saveAndFlush(Event.builder()
                .userId(user.getUserId()).sourceType("external").startsAt(ASKED_AT)
                .endsAt(ASKED_AT.plusSeconds(3600)).isAllDay(false).locationState(locationState)
                .meetingUrl(meetingUrl).autoManageExcluded(autoManageExcluded).excludedFromLearning(false)
                .status(status).createdAt(ASKED_AT).updatedAt(ASKED_AT).build());
    }

    private EventClassificationResult classificationResult() {
        return new EventClassificationResult("is_online", "online", new BigDecimal("0.9400"), "openai",
                "gpt-4o-mini-2024-08-06", "classifier-v1", "prompt-v1", "schema-v1");
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("classification concurrency latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private double reviewCount(String outcome) {
        io.micrometer.core.instrument.Counter counter = meterRegistry.find("ai_classification_reviews_total")
                .tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }
}
