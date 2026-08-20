package com.hq.backend.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hq.backend.calendar.dto.GoogleCalendarSyncEvent;
import com.hq.backend.calendar.dto.GoogleEventDateTime;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.event.classification.AiClassificationProperties;
import com.hq.backend.event.classification.ClassificationAttemptOutcome;
import com.hq.backend.event.classification.EventClassificationOrchestrator;
import com.hq.backend.plan.PlanCreationService;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class CalendarSyncServiceTest {

    @Mock private CalendarConnectionRepository connectionRepository;
    @Mock private CalendarEventWriter calendarEventWriter;
    @Mock private CalendarSyncStateWriter syncStateWriter;
    @Mock private EventClassificationOrchestrator classificationOrchestrator;
    @Mock private EventRepository eventRepository;
    @Mock private PlanCreationService planCreationService;
    @Mock private PlanRevisionRepository planRevisionRepository;
    @Mock private BytesEncryptor encryptor;
    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private RestClient.RequestBodySpec requestBodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private CalendarSyncService service;
    private final AiClassificationProperties classificationProperties = new AiClassificationProperties(
            java.net.URI.create("https://openai.test/v1"), "key", "gpt-4o-mini-2024-07-18", 3_000, 10_000,
            new AiClassificationProperties.Classification(true, 100, 1, 2, "privacy-v1",
                    "classifier-v1", "prompt-v1", "schema-v1"));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new CalendarSyncService(connectionRepository, calendarEventWriter, syncStateWriter,
                eventRepository, planCreationService, planRevisionRepository, encryptor, restClient,
                (accessToken, syncToken, now) -> Optional.of(new GoogleSyncBatch(List.of(), "next")),
                classificationOrchestrator, classificationProperties);
        ReflectionTestUtils.setField(service, "googleTokenUrl", "https://oauth.example/token");
        ReflectionTestUtils.setField(service, "googleClientId", "client");
        ReflectionTestUtils.setField(service, "googleClientSecret", "secret");
        when(encryptor.decrypt(any())).thenReturn("refresh".getBytes());
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(String.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(com.hq.backend.calendar.dto.GoogleTokenResponse.class))
                .thenReturn(new com.hq.backend.calendar.dto.GoogleTokenResponse("access", null, null, 3600L));
    }

    @Test
    void 같은_connection의_동시_실행은_첫번째만_fetch하고_두번째는_skip한다() throws Exception {
        CalendarConnection connection = connection(null);
        CountDownLatch fetchEntered = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        CalendarSyncService blockingService = new CalendarSyncService(connectionRepository, calendarEventWriter,
                syncStateWriter, eventRepository, planCreationService, planRevisionRepository, encryptor, restClient,
                (accessToken, syncToken, now) -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    fetchEntered.countDown();
                    try {
                        if (!releaseFetch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new AssertionError("fetch release latch timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return Optional.of(new GoogleSyncBatch(List.of(), "next"));
                }, classificationOrchestrator, classificationProperties);
        ReflectionTestUtils.setField(blockingService, "googleTokenUrl", "https://oauth.example/token");
        ReflectionTestUtils.setField(blockingService, "googleClientId", "client");
        ReflectionTestUtils.setField(blockingService, "googleClientSecret", "secret");
        when(connectionRepository.findById(connection.getCalendarConnectionId())).thenReturn(Optional.of(connection));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> blockingService.syncConnection(connection.getCalendarConnectionId(), true));
            assertThat(fetchEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> blockingService.syncConnection(connection.getCalendarConnectionId(), true));
            second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            releaseFetch.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            releaseFetch.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }

        verify(connectionRepository, times(1)).findById(connection.getCalendarConnectionId());
    }

    @Test
    void 만료된_토큰은_CAS_clear_성공시에만_full_sync를_한번_재시도한다() {
        CalendarConnection connection = connection("old");
        GoogleCalendarSyncClient client = new GoogleCalendarSyncClient() {
            int calls;
            @Override public Optional<GoogleSyncBatch> fetchAll(String accessToken, String token, Instant now) {
                if (calls++ == 0) throw new GoogleSyncTokenExpiredException();
                return Optional.of(new GoogleSyncBatch(List.of(), "full-token"));
            }
        };
        service = service(client);
        when(connectionRepository.findById(connection.getCalendarConnectionId())).thenReturn(Optional.of(connection));
        when(syncStateWriter.clearExpiredSyncToken(connection.getCalendarConnectionId(), "old")).thenReturn(true);
        when(syncStateWriter.advanceSyncToken(connection.getCalendarConnectionId(), null, "full-token")).thenReturn(true);

        service.syncConnection(connection.getCalendarConnectionId(), true);

        verify(syncStateWriter).clearExpiredSyncToken(connection.getCalendarConnectionId(), "old");
        verify(syncStateWriter).advanceSyncToken(connection.getCalendarConnectionId(), null, "full-token");
    }

    @Test
    void 만료된_토큰_clear_CAS가_stale이면_full_sync와_token_advance를_하지_않는다() {
        CalendarConnection connection = connection("old");
        service = service((accessToken, token, now) -> { throw new GoogleSyncTokenExpiredException(); });
        when(connectionRepository.findById(connection.getCalendarConnectionId())).thenReturn(Optional.of(connection));
        when(syncStateWriter.clearExpiredSyncToken(connection.getCalendarConnectionId(), "old")).thenReturn(false);

        service.syncConnection(connection.getCalendarConnectionId(), true);

        verify(syncStateWriter).clearExpiredSyncToken(connection.getCalendarConnectionId(), "old");
        verify(syncStateWriter, never()).advanceSyncToken(any(), any(), any());
    }

    @Test
    void full_sync도_410이면_두번째_재시도나_token_advance를_하지_않는다() {
        CalendarConnection connection = connection("old");
        GoogleCalendarSyncClient client = new GoogleCalendarSyncClient() {
            int calls;
            @Override public Optional<GoogleSyncBatch> fetchAll(String accessToken, String token, Instant now) {
                calls++;
                throw new GoogleSyncTokenExpiredException();
            }
        };
        service = service(client);
        when(connectionRepository.findById(connection.getCalendarConnectionId())).thenReturn(Optional.of(connection));
        when(syncStateWriter.clearExpiredSyncToken(connection.getCalendarConnectionId(), "old")).thenReturn(true);

        service.syncConnection(connection.getCalendarConnectionId(), true);

        verify(syncStateWriter).clearExpiredSyncToken(connection.getCalendarConnectionId(), "old");
        verify(syncStateWriter, never()).advanceSyncToken(any(), any(), any());
    }

    @Test
    void event_writer_실패면_token을_전진시키지_않는다() {
        CalendarConnection connection = connection("old");
        service = service((accessToken, token, now) -> Optional.of(new GoogleSyncBatch(List.of(event()), "next")));
        when(connectionRepository.findById(connection.getCalendarConnectionId())).thenReturn(Optional.of(connection));
        when(calendarEventWriter.upsert(any(), any(), any())).thenThrow(new IllegalStateException("db failure"));

        service.syncConnection(connection.getCalendarConnectionId(), true);

        verify(syncStateWriter, never()).advanceSyncToken(any(), any(), any());
    }

    @Test
    void sync_failure_log_never_emits_connection_user_event_or_title_identifiers(CapturedOutput output) {
        UUID connectionId = UUID.fromString("00000000-0000-0000-0000-000000000091");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000092");
        CalendarConnection connection = connection(connectionId, userId, null);
        String privateTitle = "private calendar title";
        service = service((accessToken, token, now) -> Optional.of(new GoogleSyncBatch(List.of(event(privateTitle, "secret-event-id")), "next")));
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
        when(calendarEventWriter.upsert(any(), any(), any())).thenThrow(new IllegalStateException("private exception detail"));

        service.syncConnection(connectionId, true);

        assertThat(output).contains("[CalendarSync] connection sync failed")
                .doesNotContain(connectionId.toString(), userId.toString(), "secret-event-id", privateTitle, "private exception detail");
    }

    @Test
    void 수동_sync는_CREATED를_처리해도_AI를_호출하지_않고_token을_전진시킨다() {
        CalendarConnection connection = connection(null);
        service = service((accessToken, token, now) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.of(new GoogleSyncBatch(List.of(event()), "next"));
        });
        when(connectionRepository.findByUserIdAndProvider(connection.getUserId(), "google")).thenReturn(Optional.of(connection));
        when(connectionRepository.findById(connection.getCalendarConnectionId())).thenReturn(Optional.of(connection));
        when(calendarEventWriter.upsert(any(), any(), any())).thenReturn(Optional.of(
                new CalendarUpsertResult(UUID.randomUUID(), CalendarChangeType.CREATED, false)));
        service.syncForUser(connection.getUserId());

        verifyNoInteractions(classificationOrchestrator);
        verify(syncStateWriter).advanceSyncToken(connection.getCalendarConnectionId(), null, "next");
    }

    @Test
    void scheduled_sync_uses_only_created_events_with_local_provider_budget_before_token_CAS() {
        CalendarConnection connection = connection(null);
        UUID createdId = UUID.randomUUID();
        UUID updatedId = UUID.randomUUID();
        GoogleCalendarSyncEvent created = event("created title", "created");
        GoogleCalendarSyncEvent updated = event("updated title", "updated");
        service = service((accessToken, token, now) -> Optional.of(new GoogleSyncBatch(List.of(created, updated), "next")));
        when(connectionRepository.findById(connection.getCalendarConnectionId())).thenReturn(Optional.of(connection));
        when(calendarEventWriter.upsert(any(), any(), any()))
                .thenReturn(Optional.of(new CalendarUpsertResult(createdId, CalendarChangeType.CREATED, false, 7L)))
                .thenReturn(Optional.of(new CalendarUpsertResult(updatedId, CalendarChangeType.UPDATED, false)));
        when(classificationOrchestrator.classifyCreated(connection.getUserId(), createdId, 7L, "created title", 1))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return ClassificationAttemptOutcome.PROVIDER_EMPTY;
                });

        service.syncConnection(connection.getCalendarConnectionId(), true);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                calendarEventWriter, classificationOrchestrator, syncStateWriter);
        order.verify(calendarEventWriter).upsert(connection.getUserId(), connection.getCalendarConnectionId(), created);
        order.verify(calendarEventWriter).upsert(connection.getUserId(), connection.getCalendarConnectionId(), updated);
        order.verify(classificationOrchestrator).classifyCreated(
                connection.getUserId(), createdId, 7L, "created title", 1);
        order.verify(syncStateWriter).advanceSyncToken(connection.getCalendarConnectionId(), null, "next");
        verify(classificationOrchestrator, never()).classifyCreated(
                eq(connection.getUserId()), eq(updatedId), any(), any(), any(Integer.class));
    }

    @Test
    void provider_budget_counts_only_provider_attempts_and_an_unexpected_attempt_error_still_advances_token() {
        CalendarConnection connection = connection(null);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        GoogleCalendarSyncEvent first = event("first title", "first");
        GoogleCalendarSyncEvent second = event("second title", "second");
        service = service((accessToken, token, now) -> Optional.of(new GoogleSyncBatch(List.of(first, second), "next")));
        when(connectionRepository.findById(connection.getCalendarConnectionId())).thenReturn(Optional.of(connection));
        when(calendarEventWriter.upsert(any(), any(), any()))
                .thenReturn(Optional.of(new CalendarUpsertResult(firstId, CalendarChangeType.CREATED, false, 3L)))
                .thenReturn(Optional.of(new CalendarUpsertResult(secondId, CalendarChangeType.CREATED, false, 4L)));
        when(classificationOrchestrator.classifyCreated(connection.getUserId(), firstId, 3L, "first title", 1))
                .thenReturn(ClassificationAttemptOutcome.REVIEW_CREATED);
        when(classificationOrchestrator.classifyCreated(connection.getUserId(), secondId, 4L, "second title", 0))
                .thenThrow(new IllegalStateException("review unavailable"));

        service.syncConnection(connection.getCalendarConnectionId(), true);

        verify(classificationOrchestrator).classifyCreated(connection.getUserId(), firstId, 3L, "first title", 1);
        verify(classificationOrchestrator).classifyCreated(connection.getUserId(), secondId, 4L, "second title", 0);
        verify(syncStateWriter).advanceSyncToken(connection.getCalendarConnectionId(), null, "next");
    }

    private CalendarSyncService service(GoogleCalendarSyncClient client) {
        CalendarSyncService replacement = new CalendarSyncService(connectionRepository, calendarEventWriter, syncStateWriter,
                eventRepository, planCreationService, planRevisionRepository, encryptor, restClient, client,
                classificationOrchestrator, classificationProperties);
        ReflectionTestUtils.setField(replacement, "googleTokenUrl", "https://oauth.example/token");
        ReflectionTestUtils.setField(replacement, "googleClientId", "client");
        ReflectionTestUtils.setField(replacement, "googleClientSecret", "secret");
        return replacement;
    }

    private CalendarConnection connection(String syncToken) {
        return connection(UUID.randomUUID(), UUID.randomUUID(), syncToken);
    }

    private CalendarConnection connection(UUID connectionId, UUID userId, String syncToken) {
        CalendarConnection connection = CalendarConnection.builder()
                .calendarConnectionId(connectionId)
                .userId(userId)
                .provider("google")
                .externalAccountId("account")
                .refreshTokenEnc(new byte[] {1})
                .connectedAt(Instant.now())
                .syncToken(syncToken)
                .build();
        return connection;
    }

    // #208 P0 회귀 방지 — recompute이 새 revision을 만들지 못했을 때 기존 active plan이
    // superseded로 남으면 해당 event에 active plan이 하나도 없게 된다.
    @Test
    void recompute이_빈결과면_기존_active_plan을_복원하고_token은_전진시킨다() {
        CalendarConnection connection = connection(null);
        UUID eventId = UUID.randomUUID();
        UUID originPlaceId = UUID.randomUUID();
        Event event = Event.builder().eventId(eventId).userId(connection.getUserId())
                .startsAt(Instant.parse("2026-08-20T01:00:00Z")).build();
        PlanRevision activePlan = PlanRevision.builder().eventId(eventId).originPlaceId(originPlaceId)
                .revisionNo(1).inputHash("previous-input").planStatus("active").build();

        service = service((accessToken, token, now) -> Optional.of(new GoogleSyncBatch(List.of(event()), "next")));
        when(connectionRepository.findById(connection.getCalendarConnectionId())).thenReturn(Optional.of(connection));
        when(calendarEventWriter.upsert(any(), any(), any())).thenReturn(Optional.of(
                new CalendarUpsertResult(eventId, CalendarChangeType.UPDATED, true)));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(planRevisionRepository.findByEventIdAndPlanStatus(eventId, "active")).thenReturn(Optional.of(activePlan));
        when(planCreationService.recompute(connection.getUserId(), event, originPlaceId, 2, "previous-input", null))
                .thenReturn(new PlanCreationService.RecomputeResult(Optional.empty(), false));

        service.syncConnection(connection.getCalendarConnectionId(), true);

        assertThat(activePlan.getPlanStatus()).isEqualTo("active");
        verify(planRevisionRepository, times(2)).saveAndFlush(activePlan);
        verify(syncStateWriter).advanceSyncToken(connection.getCalendarConnectionId(), null, "next");
    }

    @Test
    void recompute이_예외를_던지면_기존_active_plan을_복원한다() {
        CalendarConnection connection = connection(null);
        UUID eventId = UUID.randomUUID();
        UUID originPlaceId = UUID.randomUUID();
        Event event = Event.builder().eventId(eventId).userId(connection.getUserId())
                .startsAt(Instant.parse("2026-08-20T01:00:00Z")).build();
        PlanRevision activePlan = PlanRevision.builder().eventId(eventId).originPlaceId(originPlaceId)
                .revisionNo(1).inputHash("previous-input").planStatus("active").build();

        service = service((accessToken, token, now) -> Optional.of(new GoogleSyncBatch(List.of(event()), "next")));
        when(connectionRepository.findById(connection.getCalendarConnectionId())).thenReturn(Optional.of(connection));
        when(calendarEventWriter.upsert(any(), any(), any())).thenReturn(Optional.of(
                new CalendarUpsertResult(eventId, CalendarChangeType.UPDATED, true)));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(planRevisionRepository.findByEventIdAndPlanStatus(eventId, "active")).thenReturn(Optional.of(activePlan));
        when(planCreationService.recompute(connection.getUserId(), event, originPlaceId, 2, "previous-input", null))
                .thenThrow(new IllegalStateException("recompute blew up"));

        service.syncConnection(connection.getCalendarConnectionId(), true);

        assertThat(activePlan.getPlanStatus()).isEqualTo("active");
        verify(planRevisionRepository, times(2)).saveAndFlush(activePlan);
    }

    private GoogleCalendarSyncEvent event() {
        return event("title", "one");
    }

    private GoogleCalendarSyncEvent event(String title, String id) {
        Instant start = Instant.parse("2026-08-20T01:00:00Z");
        return new GoogleCalendarSyncEvent(id, "confirmed", title, new GoogleEventDateTime(start),
                new GoogleEventDateTime(start.plusSeconds(3600)));
    }
}
