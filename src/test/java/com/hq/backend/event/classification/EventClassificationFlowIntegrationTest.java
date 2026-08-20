package com.hq.backend.event.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.backend.calendar.CalendarConnection;
import com.hq.backend.calendar.CalendarConnectionRepository;
import com.hq.backend.calendar.CalendarSyncService;
import com.hq.backend.calendar.GoogleCalendarSyncClient;
import com.hq.backend.calendar.GoogleSyncBatch;
import com.hq.backend.calendar.dto.GoogleCalendarSyncEvent;
import com.hq.backend.calendar.dto.GoogleEventDateTime;
import com.hq.backend.calendar.dto.GoogleTokenResponse;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.consent.UserConsent;
import com.hq.backend.consent.UserConsentRepository;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventClassificationReview;
import com.hq.backend.event.EventClassificationReviewRepository;
import com.hq.backend.event.EventRepository;
import com.hq.backend.event.EventService;
import com.hq.backend.event.dto.EventReviewRequest;
import com.hq.backend.event.dto.EventUpdateRequest;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "openai.api-key=task10-fixture-key",
        "openai.classification.enabled=true",
        "openai.classification.rollout-percent=100",
        "openai.classification.max-per-sync=5",
        "openai.classification.privacy-policy-version=privacy-v1"
})
@Import(EventClassificationFlowIntegrationTest.FlowTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class EventClassificationFlowIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @TestConfiguration
    static class FlowTestConfiguration {
        @Bean
        @Primary
        RestClient flowCalendarRestClient() {
            return mock(RestClient.class);
        }

        @Bean
        @Primary
        TwoPageGoogleFixtureClient twoPageGoogleFixtureClient() {
            return new TwoPageGoogleFixtureClient();
        }
    }

    static final class TwoPageGoogleFixtureClient implements GoogleCalendarSyncClient {
        private List<GoogleCalendarSyncEvent> firstPage = List.of();
        private List<GoogleCalendarSyncEvent> secondPage = List.of();
        private int servedPages;

        void pages(List<GoogleCalendarSyncEvent> firstPage, List<GoogleCalendarSyncEvent> secondPage) {
            this.firstPage = List.copyOf(firstPage);
            this.secondPage = List.copyOf(secondPage);
            this.servedPages = 0;
        }

        int servedPages() {
            return servedPages;
        }

        @Override
        public Optional<GoogleSyncBatch> fetchAll(String accessToken, String syncToken, Instant now) {
            if (Thread.currentThread().getName().contains("scheduling")) {
                return Optional.of(new GoogleSyncBatch(List.of(), "task10-scheduled-token"));
            }
            servedPages = 2;
            List<GoogleCalendarSyncEvent> events = new ArrayList<>(firstPage);
            events.addAll(secondPage);
            return Optional.of(new GoogleSyncBatch(events, "task10-sync-token"));
        }
    }

    @org.springframework.beans.factory.annotation.Autowired private CalendarSyncService syncService;
    @org.springframework.beans.factory.annotation.Autowired private CalendarConnectionRepository connectionRepository;
    @org.springframework.beans.factory.annotation.Autowired private EventRepository eventRepository;
    @org.springframework.beans.factory.annotation.Autowired private EventClassificationReviewRepository reviewRepository;
    @org.springframework.beans.factory.annotation.Autowired private UserRepository userRepository;
    @org.springframework.beans.factory.annotation.Autowired private UserConsentRepository consentRepository;
    @org.springframework.beans.factory.annotation.Autowired private EventService eventService;
    @org.springframework.beans.factory.annotation.Autowired private BytesEncryptor encryptor;
    @org.springframework.beans.factory.annotation.Autowired private RestClient flowCalendarRestClient;
    @org.springframework.beans.factory.annotation.Autowired private TwoPageGoogleFixtureClient twoPageGoogleFixtureClient;
    @org.springframework.beans.factory.annotation.Autowired private OpenAiEventClassifier openAiEventClassifier;

    private MockRestServiceServer openAiServer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        reset(flowCalendarRestClient);
        RestClient.RequestBodyUriSpec post = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec body = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec response = mock(RestClient.ResponseSpec.class);
        when(flowCalendarRestClient.post()).thenReturn(post);
        when(post.uri(any(String.class))).thenReturn(body);
        when(body.contentType(any())).thenReturn(body);
        when(body.body(any(Object.class))).thenReturn(body);
        when(body.retrieve()).thenReturn(response);
        when(response.body(GoogleTokenResponse.class)).thenReturn(new GoogleTokenResponse("access-token", null, null, 3600L));

        RestClient.Builder builder = RestClient.builder().baseUrl("https://openai.fixture/v1");
        openAiServer = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(openAiEventClassifier, "restClient", builder.build());
    }

    @Test
    void two_page_google_sync_commits_title_free_pending_review_then_user_answer_changes_event() {
        User user = saveUser();
        agree(user);
        CalendarConnection connection = saveConnection(user);
        twoPageGoogleFixtureClient.pages(
                List.of(googleEvent("google-page-1", "온라인 기획 회의", 1)),
                List.of(googleEvent("google-page-2", "대면 고객 미팅", 2)));
        expectClassifier("online");
        expectClassifier("offline");

        sync(connection, true);

        assertThat(twoPageGoogleFixtureClient.servedPages()).isEqualTo(2);
        List<Event> events = eventRepository.findAll().stream()
                .filter(event -> user.getUserId().equals(event.getUserId()))
                .filter(event -> event.getExternalEventId() != null && event.getExternalEventId().startsWith("google-page"))
                .toList();
        assertThat(events).hasSize(2).allSatisfy(event -> {
            assertThat(event.getLocationState()).isEqualTo("undecided");
            assertThat(event.getDisplayLabel()).isNull();
            assertThat(event.getDestinationName()).isNull();
        });
        Event firstEvent = events.stream().filter(event -> "google-page-1".equals(event.getExternalEventId())).findFirst().orElseThrow();
        EventClassificationReview review = reviewRepository.findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(firstEvent.getEventId()).orElseThrow();
        assertThat(review.getTitleSnapshot()).isNull();
        assertThat(review.getTitlePurgedAt()).isNotNull();
        assertThat(review.getSuggestedValue()).isEqualTo("online");

        eventService.answerReview(user.getUserId(), firstEvent.getEventId(), new EventReviewRequest(
                review.getReviewId(), "is_online", "online"));

        assertThat(eventRepository.findById(firstEvent.getEventId()).orElseThrow().getLocationState()).isEqualTo("not_required");
        assertThat(reviewRepository.findById(review.getReviewId()).orElseThrow().getUserAnswer()).isEqualTo("online");
        openAiServer.verify();
    }

    @Test
    void consent_revoke_provider_failure_malformed_duplicate_sync_and_user_patch_never_auto_change_event() {
        User noConsent = saveUser();
        CalendarConnection noConsentConnection = saveConnection(noConsent);
        twoPageGoogleFixtureClient.pages(List.of(googleEvent("no-consent", "온라인 회의", 1)), List.of());
        sync(noConsentConnection, true);
        Event noConsentEvent = eventFor(noConsent, "no-consent");
        assertUndecidedWithoutReview(noConsentEvent);

        User revoked = saveUser();
        agree(revoked);
        revoke(revoked);
        CalendarConnection revokedConnection = saveConnection(revoked);
        twoPageGoogleFixtureClient.pages(List.of(googleEvent("revoked", "온라인 회의", 1)), List.of());
        sync(revokedConnection, true);
        assertUndecidedWithoutReview(eventFor(revoked, "revoked"));

        User timeout = saveUser();
        agree(timeout);
        CalendarConnection timeoutConnection = saveConnection(timeout);
        openAiServer.expect(requestTo("https://openai.fixture/v1/responses"))
                .andRespond(request -> { throw new java.net.SocketTimeoutException("task10 timeout canary"); });
        openAiServer.expect(requestTo("https://openai.fixture/v1/responses"))
                .andRespond(withSuccess("{\"status\":\"completed\",\"model\":\"gpt-4o-mini-2024-07-18\",\"output\":[]}", MediaType.APPLICATION_JSON));
        expectClassifier("online");
        twoPageGoogleFixtureClient.pages(List.of(googleEvent("timeout", "온라인 회의", 1)), List.of());
        sync(timeoutConnection, true);
        assertUndecidedWithoutReview(eventFor(timeout, "timeout"));

        User malformed = saveUser();
        agree(malformed);
        CalendarConnection malformedConnection = saveConnection(malformed);
        twoPageGoogleFixtureClient.pages(List.of(googleEvent("malformed", "대면 회의", 1)), List.of());
        sync(malformedConnection, true);
        assertUndecidedWithoutReview(eventFor(malformed, "malformed"));

        User duplicate = saveUser();
        agree(duplicate);
        CalendarConnection duplicateConnection = saveConnection(duplicate);
        twoPageGoogleFixtureClient.pages(List.of(googleEvent("duplicate", "온라인 회의", 1)), List.of());
        sync(duplicateConnection, true);
        Event duplicateEvent = eventFor(duplicate, "duplicate");
        EventClassificationReview review = reviewRepository.findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(duplicateEvent.getEventId()).orElseThrow();
        sync(duplicateConnection, true);
        assertThat(reviewRepository.findAll().stream().filter(item -> duplicateEvent.getEventId().equals(item.getEventId()))).hasSize(1);
        assertThat(duplicateEvent.getLocationState()).isEqualTo("undecided");

        eventService.update(duplicate.getUserId(), duplicateEvent.getEventId(), new EventUpdateRequest(
                null, null, null, null, null, null, "https://meeting.example", null, null, null));
        assertThatThrownBy(() -> eventService.answerReview(duplicate.getUserId(), duplicateEvent.getEventId(),
                new EventReviewRequest(review.getReviewId(), "is_online", "online")))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode()).isEqualTo("REVIEW_ALREADY_CLOSED");
        assertThat(eventRepository.findById(duplicateEvent.getEventId()).orElseThrow().getLocationState()).isEqualTo("undecided");
        openAiServer.verify();
    }

    @Test
    void privacy_canary_in_provider_response_and_exception_never_reaches_logs_event_or_review(CapturedOutput output) {
        String canary = "TASK10_PRIVACY_CANARY_9f1f0c";
        User user = saveUser();
        agree(user);
        CalendarConnection connection = saveConnection(user);
        twoPageGoogleFixtureClient.pages(
                List.of(googleEvent("canary-review", canary, 1)),
                List.of(googleEvent("canary-failure", "일정 " + canary, 2)));
        expectClassifier("online");
        openAiServer.expect(requestTo("https://openai.fixture/v1/responses"))
                .andRespond(withSuccess(completedResponseText(canary), MediaType.APPLICATION_JSON));

        sync(connection, true);

        List<Event> events = eventRepository.findAll().stream().filter(event -> user.getUserId().equals(event.getUserId())).toList();
        assertThat(events).allSatisfy(event -> assertThat(java.util.stream.Stream.of(
                event.getDisplayLabel(), event.getDestinationName(), event.getMeetingUrl(), event.getExternalEventId()).toList())
                .noneMatch(value -> value != null && value.contains(canary)));
        assertThat(reviewRepository.findAll().stream().filter(review -> events.stream().anyMatch(event -> event.getEventId().equals(review.getEventId()))))
                .allSatisfy(review -> assertThat(java.util.stream.Stream.of(review.getTitleSnapshot(), review.getUserAnswer(), review.getSuggestedValue(),
                        review.getModelVersion(), review.getClassifierVersion(), review.getPromptVersion(), review.getSchemaVersion()).toList())
                        .noneMatch(value -> value != null && value.contains(canary)));
        assertThat(output).doesNotContain(canary, "task10 timeout canary", "task10-fixture-key");
        openAiServer.verify();
    }

    private void expectClassifier(String expected) {
        openAiServer.expect(requestTo("https://openai.fixture/v1/responses"))
                .andRespond(withSuccess(completedResponse(expected), MediaType.APPLICATION_JSON));
    }

    private void sync(CalendarConnection connection, boolean classificationAllowed) {
        ReflectionTestUtils.invokeMethod(syncService, "syncConnection", connection.getCalendarConnectionId(), classificationAllowed);
    }

    private User saveUser() {
        return userRepository.saveAndFlush(User.builder().email("task10-" + UUID.randomUUID() + "@example.com")
                .nickname("task10-" + UUID.randomUUID().toString().substring(0, 8)).timezone("Asia/Seoul")
                .accountStatus("active").createdAt(NOW).build());
    }

    private void agree(User user) {
        consentRepository.saveAndFlush(UserConsent.builder().userId(user.getUserId()).consentType("privacy")
                .policyVersion("privacy-v1").action("agreed").isRequired(false).idempotencyKey(UUID.randomUUID())
                .recordedAt(NOW).build());
    }

    private void revoke(User user) {
        consentRepository.saveAndFlush(UserConsent.builder().userId(user.getUserId()).consentType("privacy")
                .policyVersion("privacy-v1").action("revoked").isRequired(false).idempotencyKey(UUID.randomUUID())
                .recordedAt(NOW.plusSeconds(1)).build());
    }

    private CalendarConnection saveConnection(User user) {
        return connectionRepository.saveAndFlush(CalendarConnection.builder().userId(user.getUserId()).provider("google")
                .externalAccountId("task10-account-" + UUID.randomUUID()).refreshTokenEnc(encryptor.encrypt("refresh".getBytes()))
                .connectedAt(NOW).build());
    }

    private GoogleCalendarSyncEvent googleEvent(String id, String title, int hourOffset) {
        Instant startsAt = NOW.plusSeconds(hourOffset * 3600L);
        return new GoogleCalendarSyncEvent(id, "confirmed", title, new GoogleEventDateTime(startsAt),
                new GoogleEventDateTime(startsAt.plusSeconds(3600)));
    }

    private Event eventFor(User user, String externalEventId) {
        return eventRepository.findAll().stream()
                .filter(event -> user.getUserId().equals(event.getUserId()))
                .filter(event -> externalEventId.equals(event.getExternalEventId()))
                .findFirst().orElseThrow();
    }

    private void assertUndecidedWithoutReview(Event event) {
        assertThat(event.getLocationState()).isEqualTo("undecided");
        assertThat(reviewRepository.findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(event.getEventId())).isEmpty();
    }

    private String completedResponse(String expected) {
        return completedResponseText("{\"questionType\":\"is_online\",\"suggestedValue\":\"%s\",\"confidence\":0.95}".formatted(expected));
    }

    private String completedResponseText(String text) {
        return "{\"status\":\"completed\",\"model\":\"gpt-4o-mini-2024-07-18\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":%s}]}]}"
                .formatted(OBJECT_MAPPER.valueToTree(text));
    }
}
