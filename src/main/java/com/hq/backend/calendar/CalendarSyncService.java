package com.hq.backend.calendar;

import com.hq.backend.calendar.dto.GoogleCalendarSyncEvent;
import com.hq.backend.calendar.dto.GoogleTokenResponse;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanCreationService;
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
                               GoogleCalendarSyncClient googleCalendarSyncClient) {
        this.connectionRepository = connectionRepository;
        this.calendarEventWriter = calendarEventWriter;
        this.syncStateWriter = syncStateWriter;
        this.eventRepository = eventRepository;
        this.planCreationService = planCreationService;
        this.planRevisionRepository = planRevisionRepository;
        this.calendarTokenEncryptor = calendarTokenEncryptor;
        this.restClient = restClient;
        this.googleCalendarSyncClient = googleCalendarSyncClient;
    }

    @Scheduled(fixedDelay = 300_000)
    public void syncAll() {
        connectionRepository.findAll().stream()
                .filter(connection -> connection.getRevokedAt() == null)
                .forEach(connection -> syncConnection(connection.getCalendarConnectionId(), true));
    }

    /** Manual sync deliberately has no transaction and does not enable the future AI hook. */
    public void syncForUser(UUID userId) {
        connectionRepository.findByUserIdAndProvider(userId, "google")
                .filter(connection -> connection.getRevokedAt() == null)
                .ifPresent(connection -> syncConnection(connection.getCalendarConnectionId(), false));
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
            for (GoogleCalendarSyncEvent externalEvent : fetch.batch().events()) {
                calendarEventWriter.upsert(connection.getUserId(), connectionId, externalEvent).ifPresent(results::add);
            }
            results.stream().filter(CalendarUpsertResult::requiresPlanRecompute)
                    .forEach(result -> triggerRecalculate(connection.getUserId(), result.eventId()));
            processCreatedCandidates(results, classificationAllowed);
            syncStateWriter.advanceSyncToken(connectionId, fetch.expectedTokenForCas(), fetch.batch().nextSyncToken());
        } catch (Exception exception) {
            log.warn("[CalendarSync] connection sync failed: connection_id={}", connectionId);
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

    /** Task 6 injects classifier/review orchestration here; this task intentionally creates no AI dependency. */
    void processCreatedCandidates(List<CalendarUpsertResult> results, boolean classificationAllowed) {
        if (!classificationAllowed || results.stream().noneMatch(CalendarUpsertResult::isCreated)) return;
    }

    private void triggerRecalculate(UUID userId, UUID eventId) {
        try {
            Event event = eventRepository.findById(eventId).orElse(null);
            if (event == null) return;
            planRevisionRepository.findByEventIdAndPlanStatus(eventId, "active").ifPresent(activePlan -> {
                activePlan.setPlanStatus("superseded");
                planRevisionRepository.saveAndFlush(activePlan);
                planCreationService.recompute(userId, event, activePlan.getOriginPlaceId(),
                        activePlan.getRevisionNo() + 1, activePlan.getInputHash(), null);
            });
        } catch (Exception exception) {
            log.warn("[CalendarSync] plan recompute failed: event_id={}", eventId);
        }
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
}
