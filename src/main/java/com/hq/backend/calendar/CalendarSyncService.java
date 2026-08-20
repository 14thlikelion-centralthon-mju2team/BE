package com.hq.backend.calendar;

import com.hq.backend.calendar.dto.GoogleCalendarEvent;
import com.hq.backend.calendar.dto.GoogleCalendarEventsResponse;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanCreationService;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * M4 — Google Calendar 증분 동기화.
 * syncToken 기반으로 변경된 일정만 가져와서 Event를 upsert하고,
 * 변경이 감지되면 계획을 자동 재계산한다.
 *
 * P0 수정 (#208):
 * - Google HTTP 호출은 트랜잭션 밖에서 수행
 * - DB 변경(Event upsert, syncToken 갱신)은 짧은 트랜잭션에서 명시적 save
 * - triggerRecalculate 실패 시 기존 active plan 복원
 * - syncToken은 모든 이벤트 처리 성공 후에만 저장
 */
@Service
public class CalendarSyncService {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);

    private final CalendarConnectionRepository connectionRepository;
    private final CalendarSourceRepository calendarSourceRepository;
    private final EventRepository eventRepository;
    private final PlanCreationService planCreationService;
    private final PlanRevisionRepository planRevisionRepository;
    private final BytesEncryptor calendarTokenEncryptor;
    private final RestClient restClient;
    private final TransactionTemplate transactionTemplate;

    @Value("${oauth.google.token-url}")
    private String googleTokenUrl;

    @Value("${oauth.google.client-id}")
    private String googleClientId;

    @Value("${oauth.google.client-secret}")
    private String googleClientSecret;

    @Value("${oauth.google.calendar-events-url}")
    private String googleCalendarEventsUrl;

    public CalendarSyncService(CalendarConnectionRepository connectionRepository,
                               CalendarSourceRepository calendarSourceRepository,
                               EventRepository eventRepository,
                               PlanCreationService planCreationService,
                               PlanRevisionRepository planRevisionRepository,
                               BytesEncryptor calendarTokenEncryptor,
                               RestClient restClient,
                               TransactionTemplate transactionTemplate) {
        this.connectionRepository = connectionRepository;
        this.calendarSourceRepository = calendarSourceRepository;
        this.eventRepository = eventRepository;
        this.planCreationService = planCreationService;
        this.planRevisionRepository = planRevisionRepository;
        this.calendarTokenEncryptor = calendarTokenEncryptor;
        this.restClient = restClient;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 5분마다 모든 활성 캘린더 연결에 대해 증분 동기화를 수행한다.
     * 단일 VM이므로 동시성 문제 없음 (TRD T1).
     * Google HTTP 호출은 트랜잭션 밖에서 수행하고, DB 변경은 짧은 트랜잭션으로 분리한다.
     */
    @Scheduled(fixedDelay = 300_000) // 5분
    public void syncAll() {
        connectionRepository.findAll().stream()
                .filter(c -> c.getRevokedAt() == null)
                .forEach(this::syncConnection);
    }

    /**
     * 특정 사용자의 캘린더를 즉시 동기화 (수동 트리거용).
     */
    public void syncForUser(UUID userId) {
        connectionRepository.findByUserIdAndProvider(userId, "google")
                .filter(c -> c.getRevokedAt() == null)
                .ifPresent(this::syncConnection);
    }

    // ─── orchestration (트랜잭션 없음 — Google 호출 중 DB 커넥션 미점유) ───

    private void syncConnection(CalendarConnection connection) {
        try {
            String accessToken = refreshAccessToken(connection);
            if (accessToken == null) {
                log.warn("[CalendarSync] user_id={} 토큰 갱신 실패", connection.getUserId());
                return;
            }

            String syncToken = connection.getSyncToken();
            GoogleCalendarEventsResponse response = fetchEvents(accessToken, syncToken);

            if (response == null || response.items() == null) {
                return;
            }

            processEventsAndAdvanceSyncToken(
                    connection.getUserId(),
                    connection.getCalendarConnectionId(),
                    response.items(),
                    response.nextSyncToken());
        } catch (Exception e) {
            log.error("[CalendarSync] user_id={} 동기화 실패", connection.getUserId(), e);
        }
    }

    /**
     * Event upsert 실패가 하나라도 있으면 syncToken을 유지해 다음 동기화 주기에 재처리한다.
     * package-private로 두어 transaction failure와 token 전진 조건을 독립적으로 검증한다.
     */
    void processEventsAndAdvanceSyncToken(
            UUID userId, UUID connectionId, List<GoogleCalendarEvent> events, String nextSyncToken) {
        boolean allSuccess = true;
        for (GoogleCalendarEvent gcEvent : events) {
            try {
                persistEventInTransaction(userId, connectionId, gcEvent).ifPresent(this::triggerRecalculate);
            } catch (Exception e) {
                log.error("[CalendarSync] 이벤트 처리 실패: external_id={}, cause={}",
                        gcEvent.id(), e.getMessage());
                allSuccess = false;
            }
        }

        // syncToken은 모든 이벤트 처리 성공 후에만 전진 — 실패 시 다음 주기에 재처리
        if (allSuccess && nextSyncToken != null) {
            advanceSyncTokenInTransaction(connectionId, nextSyncToken);
        }

        log.debug("[CalendarSync] user_id={} 동기화 완료, events={}, tokenAdvanced={}",
                userId, events.size(), allSuccess && nextSyncToken != null);
    }

    // ─── DB 변경 (실제 짧은 트랜잭션) ───

    private Optional<RecalculationRequest> persistEventInTransaction(
            UUID userId, UUID connectionId, GoogleCalendarEvent gcEvent) {
        Optional<RecalculationRequest> result = transactionTemplate.execute(
                status -> persistEvent(userId, connectionId, gcEvent));
        return result == null ? Optional.empty() : result;
    }

    private Optional<RecalculationRequest> persistEvent(
            UUID userId, UUID connectionId, GoogleCalendarEvent gcEvent) {
        if (gcEvent.id() == null) return Optional.empty();

        CalendarSource source = calendarSourceRepository
                .findByCalendarConnectionIdAndIsDefaultTrueAndDeletedAtIsNull(connectionId)
                .orElseGet(() -> calendarSourceRepository.save(CalendarSource.builder()
                        .calendarConnectionId(connectionId)
                        .externalCalendarId("primary")
                        .displayName("내 캘린더")
                        .isWritable(true)
                        .isDefault(true)
                        .syncEnabled(true)
                        .build()));

        if (gcEvent.start() == null || gcEvent.start().dateTime() == null) {
            return Optional.empty();
        }

        Optional<Event> existingOpt = eventRepository.findByCalendarSourceIdAndExternalEventId(
                source.getCalendarSourceId(), gcEvent.id());

        if ("cancelled".equals(gcEvent.status())) {
            existingOpt.ifPresent(event -> {
                if (!"cancelled".equals(event.getStatus())) {
                    event.setStatus("cancelled");
                    eventRepository.save(event);
                    log.info("[CalendarSync] 일정 삭제 감지: event_id={}", event.getEventId());
                }
            });
            return Optional.empty();
        }

        Instant startsAt = gcEvent.start().dateTime();
        Instant endsAt = gcEvent.end() != null ? gcEvent.end().dateTime() : startsAt.plusSeconds(3600);

        if (existingOpt.isPresent()) {
            Event event = existingOpt.get();
            boolean timeChanged = !startsAt.equals(event.getStartsAt()) || !endsAt.equals(event.getEndsAt());
            if (!timeChanged) return Optional.empty();

            event.setStartsAt(startsAt);
            event.setEndsAt(endsAt);
            event.setUpdatedAt(Instant.now());
            eventRepository.save(event);
            log.info("[CalendarSync] 일정 시각 변경: event_id={}", event.getEventId());
            return Optional.of(new RecalculationRequest(userId, event));
        }

        Event newEvent = Event.builder()
                .userId(userId)
                .calendarSourceId(source.getCalendarSourceId())
                .externalEventId(gcEvent.id())
                .sourceType("external")
                .startsAt(startsAt)
                .endsAt(endsAt)
                .isAllDay(false)
                .locationState("undecided")
                .autoManageExcluded(false)
                .status("planned")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        eventRepository.save(newEvent);
        log.info("[CalendarSync] 새 일정 생성: event_id={}, external_id={}", newEvent.getEventId(), gcEvent.id());
        return Optional.empty();
    }

    private void advanceSyncTokenInTransaction(UUID connectionId, String nextToken) {
        transactionTemplate.executeWithoutResult(status -> connectionRepository.findById(connectionId).ifPresent(conn -> {
            conn.setSyncToken(nextToken);
            connectionRepository.save(conn);
        }));
    }

    private record RecalculationRequest(UUID userId, Event event) {
    }

    // ─── 재계산 (새 active revision이 없으면 기존 plan 복원) ───

    private void triggerRecalculate(RecalculationRequest request) {
        triggerRecalculate(request.userId(), request.event());
    }

    void triggerRecalculate(UUID userId, Event event) {
        var activePlanOpt = planRevisionRepository.findByEventIdAndPlanStatus(event.getEventId(), "active");
        if (activePlanOpt.isEmpty()) return;

        PlanRevision activePlan = activePlanOpt.get();
        String originalStatus = activePlan.getPlanStatus();
        try {
            // uq_active_plan_per_event 제약상 새 revision INSERT 전에 기존 active를 비활성화한다.
            activePlan.setPlanStatus("superseded");
            planRevisionRepository.saveAndFlush(activePlan);

            PlanCreationService.RecomputeResult result = planCreationService.recompute(
                    userId, event, activePlan.getOriginPlaceId(),
                    activePlan.getRevisionNo() + 1,
                    activePlan.getInputHash(), null);
            if (result.revision().isEmpty()) {
                restoreActivePlan(activePlan, originalStatus, event, "새 revision이 생성되지 않음");
            }
        } catch (Exception e) {
            restoreActivePlan(activePlan, originalStatus, event, e.getMessage());
        }
    }

    private void restoreActivePlan(PlanRevision activePlan, String originalStatus, Event event, String cause) {
        log.warn("[CalendarSync] 재계산 실패/무변경, 기존 plan 복원: event_id={}, cause={}",
                event.getEventId(), cause);
        activePlan.setPlanStatus(originalStatus);
        planRevisionRepository.saveAndFlush(activePlan);
    }

    // ─── Google 외부 호출 (트랜잭션 밖) ───

    private GoogleCalendarEventsResponse fetchEvents(String accessToken, String syncToken) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(googleCalendarEventsUrl)
                .queryParam("singleEvents", true)
                .queryParam("orderBy", "startTime");

        if (syncToken != null) {
            builder.queryParam("syncToken", syncToken);
        } else {
            builder.queryParam("timeMin", Instant.now().toString());
            builder.queryParam("timeMax", Instant.now().plusSeconds(30L * 24 * 3600).toString());
        }

        URI uri = builder.encode().build().toUri();

        try {
            return restClient.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(GoogleCalendarEventsResponse.class);
        } catch (RestClientException e) {
            log.warn("[CalendarSync] Google API 호출 실패: {}", e.getMessage());
            return null;
        }
    }

    private String refreshAccessToken(CalendarConnection connection) {
        byte[] decrypted = calendarTokenEncryptor.decrypt(connection.getRefreshTokenEnc());
        String refreshToken = new String(decrypted, StandardCharsets.UTF_8);

        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        form.add("refresh_token", refreshToken);
        form.add("client_id", googleClientId);
        form.add("client_secret", googleClientSecret);
        form.add("grant_type", "refresh_token");

        try {
            var response = restClient.post()
                    .uri(googleTokenUrl)
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(com.hq.backend.calendar.dto.GoogleTokenResponse.class);
            return response != null ? response.accessToken() : null;
        } catch (RestClientException e) {
            return null;
        }
    }
}
