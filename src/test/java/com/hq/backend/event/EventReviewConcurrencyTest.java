package com.hq.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.dto.EventReviewRequest;
import com.hq.backend.event.dto.EventUpdateRequest;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class EventReviewConcurrencyTest {

    private static final Instant NOW = Instant.parse("2026-08-20T03:00:00Z");

    @Autowired private EventService eventService;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationReviewRepository reviewRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    void 같은_review에_동시에_두번_답하면_정확히_하나만_성공한다() throws Exception {
        Fixture fixture = fixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> first = executor.submit(() -> answerAfter(start, fixture));
            Future<Object> second = executor.submit(() -> answerAfter(start, fixture));
            start.countDown();

            List<Object> outcomes = List.of(first.get(5, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(outcome -> outcome instanceof com.hq.backend.event.dto.EventReviewResponse)
                    .hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> outcome instanceof ApiException).hasSize(1);
            ApiException loser = (ApiException) outcomes.stream().filter(ApiException.class::isInstance).findFirst().orElseThrow();
            assertThat(loser.getCode()).isIn("REVIEW_ALREADY_CLOSED", "REVIEW_STALE");
            EventClassificationReview review = reviewRepository.findById(fixture.reviewId()).orElseThrow();
            assertThat(review.getUserAnswer()).isEqualTo("online");
            assertThat(review.getAnsweredAt()).isNotNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void PATCH가_event_lock을_먼저_획득한_race에서는_사용자_PATCH가_wins하고_review가_null답변으로_닫힌다() throws Exception {
        Fixture fixture = fixture();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch patchWantsEventLock = new CountDownLatch(1);
        EventRepository originalRepository = eventRepository;
        EventRepository lockAwareRepository = lockAwareRepository(originalRepository, fixture, patchWantsEventLock);
        ReflectionTestUtils.setField(eventService, "eventRepository", lockAwareRepository);
        try {
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                eventRepository.findByIdForUpdate(fixture.eventId()).orElseThrow();
                lockHeld.countDown();
                await(releaseLock);
            }));
            await(lockHeld);
            Future<?> patch = executor.submit(() -> {
                eventService.update(fixture.userId(), fixture.eventId(), new EventUpdateRequest(
                        null, null, null, null, null, null, "https://meeting.example", null, null, null));
            });
            await(patchWantsEventLock);
            assertThatThrownBy(() -> patch.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            Future<Object> answer = executor.submit(() -> answer(fixture));
            releaseLock.countDown();
            holder.get(5, java.util.concurrent.TimeUnit.SECONDS);
            patch.get(5, java.util.concurrent.TimeUnit.SECONDS);

            Object answerOutcome = answer.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(answerOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) answerOutcome).getCode()).isEqualTo("REVIEW_ALREADY_CLOSED");
            Event event = eventRepository.findById(fixture.eventId()).orElseThrow();
            EventClassificationReview review = reviewRepository.findById(fixture.reviewId()).orElseThrow();
            assertThat(event.getMeetingUrl()).isEqualTo("https://meeting.example");
            assertThat(review.getAnsweredAt()).isNotNull();
            assertThat(review.getUserAnswer()).isNull();
        } finally {
            releaseLock.countDown();
            ReflectionTestUtils.setField(eventService, "eventRepository", originalRepository);
            executor.shutdownNow();
        }
    }

    private Object answerAfter(CountDownLatch start, Fixture fixture) {
        await(start);
        return answer(fixture);
    }

    private Object answer(Fixture fixture) {
        try {
            return eventService.answerReview(fixture.userId(), fixture.eventId(), new EventReviewRequest(
                    fixture.reviewId(), "is_online", "online"));
        } catch (ApiException exception) {
            return exception;
        }
    }

    private EventRepository lockAwareRepository(
            EventRepository delegate, Fixture fixture, CountDownLatch patchWantsEventLock) {
        return (EventRepository) Proxy.newProxyInstance(
                EventRepository.class.getClassLoader(), new Class<?>[]{EventRepository.class}, (proxy, method, args) -> {
                    if ("findOwnedForUpdate".equals(method.getName())
                            && args.length == 2
                            && fixture.eventId().equals(args[0])
                            && fixture.userId().equals(args[1])) {
                        patchWantsEventLock.countDown();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private Fixture fixture() {
        User user = userRepository.saveAndFlush(User.builder()
                .email("review-race-" + UUID.randomUUID() + "@example.com")
                .nickname("review-race-" + UUID.randomUUID().toString().substring(0, 8))
                .timezone("Asia/Seoul").accountStatus("active").createdAt(NOW).build());
        Event event = eventRepository.saveAndFlush(Event.builder()
                .userId(user.getUserId()).sourceType("external").startsAt(NOW.plusSeconds(3600))
                .isAllDay(false).locationState("undecided").autoManageExcluded(false)
                .excludedFromLearning(false).status("planned").createdAt(NOW).updatedAt(NOW).build());
        EventClassificationReview review = reviewRepository.saveAndFlush(EventClassificationReview.builder()
                .eventId(event.getEventId()).questionType("is_online").suggestedValue("online")
                .classificationConfidence(new BigDecimal("0.9400")).askedAt(NOW).titlePurgedAt(NOW)
                .provider("openai").modelVersion("gpt-5-mini").classifierVersion("classifier-v1")
                .promptVersion("prompt-v1").schemaVersion("schema-v1").build());
        return new Fixture(user.getUserId(), event.getEventId(), review.getReviewId());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("동시성 테스트 latch 대기 시간이 5초를 초과했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(UUID userId, UUID eventId, UUID reviewId) {}
}
