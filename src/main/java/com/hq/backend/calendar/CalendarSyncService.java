package com.hq.backend.calendar;

import com.hq.backend.calendar.dto.GoogleCalendarSyncEvent;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.plan.PlanCreationService;
import com.hq.backend.plan.PlanRevisionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * M4 — Google Calendar 증분 동기화.
 * syncToken 기반으로 변경된 일정만 가져와서 Event를 upsert하고,
 * 변경이 감지되면 계획을 자동 재계산한다.
 *
 * TRD §13.1: 캘린더 동기화는 ext_uid 기준 upsert.
 * TRD CAL-03: 사용자가 수정한 필드(place_need_by='user')는 동기화가 덮지 않는다.
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
    private final GoogleCalendarSyncClient googleCalendarSyncClient;

    @Value("${oauth.google.token-url}")
    private String googleTokenUrl;

    @Value("${oauth.google.client-id}")
    private String googleClientId;

    @Value("${oauth.google.client-secret}")
    private String googleClientSecret;

    public CalendarSyncService(CalendarConnectionRepository connectionRepository,
                               CalendarSourceRepository calendarSourceRepository,
                               EventRepository eventRepository,
                               PlanCreationService planCreationService,
                               PlanRevisionRepository planRevisionRepository,
                               BytesEncryptor calendarTokenEncryptor,
                               RestClient restClient,
                               GoogleCalendarSyncClient googleCalendarSyncClient) {
        this.connectionRepository = connectionRepository;
        this.calendarSourceRepository = calendarSourceRepository;
        this.eventRepository = eventRepository;
        this.planCreationService = planCreationService;
        this.planRevisionRepository = planRevisionRepository;
        this.calendarTokenEncryptor = calendarTokenEncryptor;
        this.restClient = restClient;
        this.googleCalendarSyncClient = googleCalendarSyncClient;
    }

    /**
     * 5분마다 모든 활성 캘린더 연결에 대해 증분 동기화를 수행한다.
     * 단일 VM이므로 동시성 문제 없음 (TRD T1).
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
    @Transactional
    public void syncForUser(UUID userId) {
        connectionRepository.findByUserIdAndProvider(userId, "google")
                .filter(c -> c.getRevokedAt() == null)
                .ifPresent(this::syncConnection);
    }

    private void syncConnection(CalendarConnection connection) {
        try {
            String accessToken = refreshAccessToken(connection);
            if (accessToken == null) {
                log.warn("[CalendarSync] user_id={} 토큰 갱신 실패", connection.getUserId());
                return;
            }

            String syncToken = connection.getSyncToken();
            Optional<GoogleSyncBatch> batch = googleCalendarSyncClient.fetchAll(accessToken, syncToken, Instant.now());
            if (batch.isEmpty()) {
                return;
            }

            for (GoogleCalendarSyncEvent gcEvent : batch.get().events()) {
                processEvent(connection, gcEvent);
            }

            // syncToken 갱신 (다음 동기화에서 변경분만 조회)
            if (batch.get().nextSyncToken() != null) {
                connection.setSyncToken(batch.get().nextSyncToken());
            }

            log.debug("[CalendarSync] user_id={} 동기화 완료, events={}", connection.getUserId(),
                    batch.get().events().size());
        } catch (Exception e) {
            log.error("[CalendarSync] user_id={} 동기화 실패", connection.getUserId(), e);
        }
    }

    private void processEvent(CalendarConnection connection, GoogleCalendarSyncEvent gcEvent) {
        if (gcEvent.id() == null) return;
        UUID userId = connection.getUserId();
        CalendarSource source = calendarSourceRepository
                .findByCalendarConnectionIdAndIsDefaultTrueAndDeletedAtIsNull(connection.getCalendarConnectionId())
                .orElseGet(() -> calendarSourceRepository.save(CalendarSource.builder()
                        .calendarConnectionId(connection.getCalendarConnectionId())
                        .externalCalendarId("primary")
                        .displayName("내 캘린더")
                        .isWritable(true)
                        .isDefault(true)
                        .syncEnabled(true)
                        .build()));

        // 시간 정보가 없는 이벤트(종일 이벤트 중 dateTime이 없는 것)는 스킵
        if (gcEvent.start() == null || gcEvent.start().dateTime() == null) {
            return;
        }

        Optional<Event> existingOpt = eventRepository.findByCalendarSourceIdAndExternalEventId(
                source.getCalendarSourceId(), gcEvent.id());

        if ("cancelled".equals(gcEvent.status())) {
            // 삭제된 일정 → cancelled 처리
            existingOpt.ifPresent(event -> {
                if (!"cancelled".equals(event.getStatus())) {
                    event.setStatus("cancelled");
                    log.info("[CalendarSync] 일정 삭제 감지: event_id={}", event.getEventId());
                }
            });
            return;
        }

        Instant startsAt = gcEvent.start().dateTime();
        Instant endsAt = gcEvent.end() != null ? gcEvent.end().dateTime() : startsAt.plusSeconds(3600);

        if (existingOpt.isPresent()) {
            // 기존 일정 업데이트 — 시각 변경만 동기화, 사용자 수정 필드는 건드리지 않음 (CAL-03)
            Event event = existingOpt.get();
            boolean timeChanged = !startsAt.equals(event.getStartsAt()) || !endsAt.equals(event.getEndsAt());

            if (timeChanged) {
                event.setStartsAt(startsAt);
                event.setEndsAt(endsAt);
                event.setUpdatedAt(Instant.now());
                // 시각이 바뀌면 계획 재계산 트리거
                triggerRecalculate(userId, event);
                log.info("[CalendarSync] 일정 시각 변경: event_id={}", event.getEventId());
            }
        } else {
            // 새 일정 생성
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

            // displayLabel: 외부 일정 제목 원문은 저장하지 않음 (TRD 절대 원칙 8)
            // 분류 후 폐기 — displayLabel은 사용자가 승인할 때까지 null
            eventRepository.save(newEvent);
            log.info("[CalendarSync] 새 일정 생성: event_id={}, external_id={}", newEvent.getEventId(), gcEvent.id());

            // 목적지가 없으면 계획을 바로 만들 수 없음 — location_state=undecided
            // FE에서 목적지 입력 후 POST /events/{id}/plan-trigger 등으로 계획 생성
        }
    }

    private void triggerRecalculate(UUID userId, Event event) {
        try {
            // 현재 활성 리비전을 찾아서 재계산
            var activePlanOpt = planRevisionRepository.findByEventIdAndPlanStatus(event.getEventId(), "active");
            if (activePlanOpt.isEmpty()) return;

            var activePlan = activePlanOpt.get();

            // uq_active_plan_per_event 제약: 새 리비전 INSERT 전에 기존을 superseded로 전환 + flush
            activePlan.setPlanStatus("superseded");
            planRevisionRepository.saveAndFlush(activePlan);

            planCreationService.recompute(
                    userId, event, activePlan.getOriginPlaceId(),
                    activePlan.getRevisionNo() + 1,
                    activePlan.getInputHash(), null);
        } catch (Exception e) {
            log.warn("[CalendarSync] 재계산 실패: event_id={}", event.getEventId(), e);
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
