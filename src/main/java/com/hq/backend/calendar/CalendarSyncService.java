package com.hq.backend.calendar;

import com.hq.backend.calendar.dto.GoogleCalendarSyncEvent;
import com.hq.backend.calendar.dto.GoogleTokenResponse;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.event.classification.AiClassificationProperties;
import com.hq.backend.event.classification.ClassificationAttemptOutcome;
import com.hq.backend.event.classification.EventClassificationOrchestrator;
import com.hq.backend.plan.PlanCreationService;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Coordinates external I/O; every calendar write is delegated to a short writer transaction. */
@Service
public class CalendarSyncService {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);

    private final CalendarConnectionRepository connectionRepository;
    private final CalendarEventWriter calendarEventWriter;
    private final CalendarSyncStateWriter syncStateWriter;
    private final EventRepository eventRepository;
    private final PlanCreationService planCreationService;
    private final PlanRevisionRepository planRevisionRepository;
    private final BytesEncryptor calendarTokenEncryptor;
    private final RestClient restClient;
    private final GoogleCalendarSyncClient googleCalendarSyncClient;
    private final EventClassificationOrchestrator classificationOrchestrator;
    private final AiClassificationProperties classificationProperties;
    private final Set<UUID> runningConnectionIds = ConcurrentHashMap.newKeySet();

    @Value("${oauth.google.token-url}")
    private String googleTokenUrl;
    @Value("${oauth.google.client-id}")
    private String googleClientId;
    @Value("${oauth.google.client-secret}")
    private String googleClientSecret;

    public CalendarSyncService(CalendarConnectionRepository connectionRepository,
                               CalendarEventWriter calendarEventWriter,
                               CalendarSyncStateWriter syncStateWriter,
                               EventRepository eventRepository,
                               PlanCreationService planCreationService,
                               PlanRevisionRepository planRevisionRepository,
                               BytesEncryptor calendarTokenEncryptor,
                               RestClient restClient,
                               GoogleCalendarSyncClient googleCalendarSyncClient,
                               EventClassificationOrchestrator classificationOrchestrator,
                               AiClassificationProperties classificationProperties) {
        this.connectionRepository = connectionRepository;
        this.calendarEventWriter = calendarEventWriter;
        this.syncStateWriter = syncStateWriter;
        this.eventRepository = eventRepository;
        this.planCreationService = planCreationService;
        this.planRevisionRepository = planRevisionRepository;
        this.calendarTokenEncryptor = calendarTokenEncryptor;
        this.restClient = restClient;
        this.googleCalendarSyncClient = googleCalendarSyncClient;
        this.classificationOrchestrator = classificationOrchestrator;
        this.classificationProperties = classificationProperties;
    }

    @Scheduled(fixedDelay = 300_000)
    public void syncAll() {
        connectionRepository.findAll().stream()
                .filter(connection -> connection.getRevokedAt() == null)
                .forEach(connection -> syncConnection(connection.getCalendarConnectionId(), true));
    }

    /** Manual sync deliberately has no transaction and does not enable the future AI hook. */
    public void syncForUser(UUID userId) {
        // 연결이 없으면 조용히 넘어가지 않는다 — 아무것도 안 한 호출이 FE 배너에서
        // "동기화 완료"로 보이면 안 된다.
        CalendarConnection connection = connectionRepository
                .findByUserIdAndProvider(userId, "google")
                .filter(found -> found.getRevokedAt() == null)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "CALENDAR_NOT_CONNECTED", "연결된 구글 캘린더가 없습니다."));
        syncConnection(connection.getCalendarConnectionId(), false);
    }

    void syncConnection(UUID connectionId, boolean classificationAllowed) {
        if (!runningConnectionIds.add(connectionId)) return;
        try {
            CalendarConnection connection = connectionRepository.findById(connectionId)
                    .filter(found -> found.getRevokedAt() == null).orElse(null);
            if (connection == null) return;
            String accessToken = refreshAccessToken(connection);
            if (accessToken == null) return;

            SyncFetchResult fetch = fetchWithExpiredTokenRecovery(connectionId, accessToken, connection.getSyncToken())
                    .orElse(null);
            if (fetch == null || fetch.batch().nextSyncToken() == null) return;

            List<CalendarUpsertResult> results = new ArrayList<>();
            List<CreatedCandidate> createdCandidates = new ArrayList<>();
            for (GoogleCalendarSyncEvent externalEvent : fetch.batch().events()) {
                calendarEventWriter.upsert(connection.getUserId(), connectionId, externalEvent).ifPresent(result -> {
                    results.add(result);
                    if (result.isCreated()) {
                        createdCandidates.add(new CreatedCandidate(
                                result.eventId(), result.eventRevision(), externalEvent.summary()));
                    }
                });
            }
            results.stream().filter(CalendarUpsertResult::requiresPlanRecompute)
                    .forEach(result -> triggerRecalculate(connection.getUserId(), result.eventId()));
            processCreatedCandidates(connection.getUserId(), createdCandidates, classificationAllowed);
            syncStateWriter.advanceSyncToken(connectionId, fetch.expectedTokenForCas(), fetch.batch().nextSyncToken());
        } catch (Exception exception) {
            log.warn("[CalendarSync] connection sync failed");
        } finally {
            runningConnectionIds.remove(connectionId);
        }
    }

    private Optional<SyncFetchResult> fetchWithExpiredTokenRecovery(
            UUID connectionId, String accessToken, String expectedToken) {
        try {
            return googleCalendarSyncClient.fetchAll(accessToken, expectedToken, Instant.now())
                    .map(batch -> new SyncFetchResult(batch, expectedToken));
        } catch (GoogleSyncTokenExpiredException expired) {
            if (!syncStateWriter.clearExpiredSyncToken(connectionId, expectedToken)) return Optional.empty();
            try {
                return googleCalendarSyncClient.fetchAll(accessToken, null, Instant.now())
                        .map(batch -> new SyncFetchResult(batch, null));
            } catch (GoogleSyncTokenExpiredException secondExpired) {
                return Optional.empty();
            }
        }
    }

    void processCreatedCandidates(UUID userId, List<CreatedCandidate> candidates, boolean classificationAllowed) {
        if (!classificationAllowed) return;
        int providerCalls = 0;
        for (CreatedCandidate candidate : candidates) {
            try {
                ClassificationAttemptOutcome outcome = classificationOrchestrator.classifyCreated(
                        userId, candidate.eventId(), candidate.eventRevision(), candidate.rawTitle(),
                        classificationProperties.classification().maxPerSync() - providerCalls);
                if (outcome.providerCalled()) {
                    providerCalls++;
                }
            } catch (RuntimeException ignored) {
                // A best-effort review must never prevent sync-token CAS.
            }
        }
    }

    private void triggerRecalculate(UUID userId, UUID eventId) {
        try {
            Event event = eventRepository.findById(eventId).orElse(null);
            if (event == null) return;
            planRevisionRepository.findByEventIdAndPlanStatus(eventId, "active").ifPresent(activePlan -> {
                String originalStatus = activePlan.getPlanStatus();
                try {
                    // uq_active_plan_per_event 제약상 새 revision INSERT 전에 기존 active를 비활성화한다.
                    activePlan.setPlanStatus("superseded");
                    planRevisionRepository.saveAndFlush(activePlan);
                    PlanCreationService.RecomputeResult result = planCreationService.recompute(userId, event,
                            activePlan.getOriginPlaceId(), activePlan.getRevisionNo() + 1,
                            activePlan.getInputHash(), null);
                    // #208 P0: 새 active revision이 생기지 않았는데 기존 것을 superseded로 두면
                    // 해당 event에 active plan이 하나도 남지 않는다. 반드시 되돌린다.
                    if (result.revision().isEmpty()) {
                        restoreActivePlan(activePlan, originalStatus);
                    }
                } catch (RuntimeException recomputeFailure) {
                    restoreActivePlan(activePlan, originalStatus);
                    throw recomputeFailure;
                }
            });
        } catch (Exception exception) {
            log.warn("[CalendarSync] plan recompute failed");
        }
    }

    private void restoreActivePlan(PlanRevision activePlan, String originalStatus) {
        activePlan.setPlanStatus(originalStatus);
        planRevisionRepository.saveAndFlush(activePlan);
    }

    private String refreshAccessToken(CalendarConnection connection) {
        try {
            byte[] decrypted = calendarTokenEncryptor.decrypt(connection.getRefreshTokenEnc());
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("refresh_token", new String(decrypted, StandardCharsets.UTF_8));
            form.add("client_id", googleClientId);
            form.add("client_secret", googleClientSecret);
            form.add("grant_type", "refresh_token");
            GoogleTokenResponse response = restClient.post().uri(googleTokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve()
                    .body(GoogleTokenResponse.class);
            return response == null ? null : response.accessToken();
        } catch (RestClientException | IllegalStateException exception) {
            return null;
        }
    }

    private record SyncFetchResult(GoogleSyncBatch batch, String expectedTokenForCas) {
    }

    record CreatedCandidate(UUID eventId, Long eventRevision, String rawTitle) {
    }
}
