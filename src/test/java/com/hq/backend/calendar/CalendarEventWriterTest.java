package com.hq.backend.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hq.backend.calendar.dto.GoogleCalendarSyncEvent;
import com.hq.backend.calendar.dto.GoogleEventDateTime;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CalendarEventWriterTest {

    @Autowired private CalendarEventWriter calendarEventWriter;
    @Autowired private CalendarConnectionRepository connectionRepository;
    @Autowired private CalendarSourceRepository sourceRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CalendarSyncStateWriter syncStateWriter;

    @Test
    void 신규_일정은_제목을_저장하지_않고_CREATED로_반환한다() {
        CalendarConnection connection = saveConnection();

        CalendarUpsertResult result = calendarEventWriter.upsert(
                connection.getUserId(), connection.getCalendarConnectionId(), event("one", "confirmed", 0))
                .orElseThrow();

        Event saved = eventRepository.findById(result.eventId()).orElseThrow();
        assertThat(result.changeType()).isEqualTo(CalendarChangeType.CREATED);
        assertThat(result.requiresPlanRecompute()).isFalse();
        assertThat(saved.getDisplayLabel()).isNull();
        assertThat(saved.getExternalEventId()).isEqualTo("one");
        assertThat(sourceRepository.findByCalendarConnectionIdAndIsDefaultTrueAndDeletedAtIsNull(
                connection.getCalendarConnectionId())).isPresent();
    }

    @Test
    void 동일_일정은_UNCHANGED로_반환한다() {
        CalendarConnection connection = saveConnection();
        calendarEventWriter.upsert(connection.getUserId(), connection.getCalendarConnectionId(), event("same", "confirmed", 0));

        CalendarUpsertResult result = calendarEventWriter.upsert(
                connection.getUserId(), connection.getCalendarConnectionId(), event("same", "confirmed", 0)).orElseThrow();

        assertThat(result.changeType()).isEqualTo(CalendarChangeType.UNCHANGED);
        assertThat(result.requiresPlanRecompute()).isFalse();
    }

    @Test
    void 시각만_변경된_일정은_UPDATED와_재계산_플래그를_반환한다() {
        CalendarConnection connection = saveConnection();
        calendarEventWriter.upsert(connection.getUserId(), connection.getCalendarConnectionId(), event("moved", "confirmed", 0));

        CalendarUpsertResult result = calendarEventWriter.upsert(
                connection.getUserId(), connection.getCalendarConnectionId(), event("moved", "confirmed", 3600)).orElseThrow();

        assertThat(result.changeType()).isEqualTo(CalendarChangeType.UPDATED);
        assertThat(result.requiresPlanRecompute()).isTrue();
    }

    @Test
    void 시간_없는_취소_tombstone은_기존_일정을_CANCELLED로_반환하고_없는_일정은_skip한다() {
        CalendarConnection connection = saveConnection();
        CalendarUpsertResult created = calendarEventWriter.upsert(
                connection.getUserId(), connection.getCalendarConnectionId(), event("cancel", "confirmed", 0)).orElseThrow();

        CalendarUpsertResult cancelled = calendarEventWriter.upsert(
                connection.getUserId(), connection.getCalendarConnectionId(), cancellationTombstone("cancel")).orElseThrow();

        assertThat(cancelled.changeType()).isEqualTo(CalendarChangeType.CANCELLED);
        assertThat(eventRepository.findById(created.eventId()).orElseThrow().getStatus()).isEqualTo("cancelled");
        assertThat(calendarEventWriter.upsert(
                connection.getUserId(), connection.getCalendarConnectionId(), event("missing", "cancelled", 0))).isEmpty();
    }

    @Test
    void 종일_일정은_정상_skip한다() {
        CalendarConnection connection = saveConnection();
        GoogleCalendarSyncEvent allDay = new GoogleCalendarSyncEvent(
                "all-day", "confirmed", "private", new GoogleEventDateTime(null), new GoogleEventDateTime(null));

        assertThat(calendarEventWriter.upsert(
                connection.getUserId(), connection.getCalendarConnectionId(), allDay)).isEmpty();
    }

    @Test
    void 기존_일정의_잘못된_시간_업데이트는_UNCHANGED로_숨기지_않고_DB_오류를_전파한다() {
        CalendarConnection connection = saveConnection();
        calendarEventWriter.upsert(connection.getUserId(), connection.getCalendarConnectionId(), event("bad-time", "confirmed", 0));
        Instant start = Instant.parse("2026-08-20T01:00:00Z");
        GoogleCalendarSyncEvent invalidUpdate = new GoogleCalendarSyncEvent("bad-time", "confirmed", "title",
                new GoogleEventDateTime(start.plusSeconds(7200)), new GoogleEventDateTime(start));

        assertThatThrownBy(() -> calendarEventWriter.upsert(
                connection.getUserId(), connection.getCalendarConnectionId(), invalidUpdate))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void 동시에_같은_외부_일정을_insert해도_충돌_호출은_UNCHANGED로_정상화한다() throws Exception {
        CalendarConnection connection = saveConnection();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CalendarUpsertResult>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return calendarEventWriter.upsert(
                            connection.getUserId(), connection.getCalendarConnectionId(), event("racing", "confirmed", 0))
                            .orElseThrow();
                }));
            }
            ready.await();
            start.countDown();

            List<CalendarChangeType> changeTypes = new ArrayList<>();
            for (Future<CalendarUpsertResult> result : results) {
                changeTypes.add(result.get().changeType());
            }
            assertThat(changeTypes)
                    .containsExactlyInAnyOrder(CalendarChangeType.CREATED, CalendarChangeType.UNCHANGED);
            assertThat(sourceRepository.findByCalendarConnectionIdAndDeletedAtIsNullOrderByIsDefaultDescDisplayNameAsc(
                    connection.getCalendarConnectionId())).hasSize(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sync_token_CAS는_initial_replace_clear의_기대값이_stale이면_실패한다() {
        CalendarConnection connection = saveConnection();
        UUID id = connection.getCalendarConnectionId();

        assertThat(syncStateWriter.advanceSyncToken(id, null, "one")).isTrue();
        assertThat(syncStateWriter.advanceSyncToken(id, null, "two")).isFalse();
        assertThat(syncStateWriter.advanceSyncToken(id, "stale", "two")).isFalse();
        assertThat(syncStateWriter.advanceSyncToken(id, "one", "two")).isTrue();
        assertThat(syncStateWriter.clearExpiredSyncToken(id, "stale")).isFalse();
        assertThat(syncStateWriter.clearExpiredSyncToken(id, "two")).isTrue();
    }

    private CalendarConnection saveConnection() {
        User user = userRepository.saveAndFlush(User.builder()
                .email("calendar-writer-" + UUID.randomUUID() + "@example.com")
                .nickname("writer-" + UUID.randomUUID().toString().substring(0, 8))
                .timezone("Asia/Seoul")
                .createdAt(Instant.now())
                .accountStatus("active")
                .build());
        return connectionRepository.saveAndFlush(CalendarConnection.builder()
                .userId(user.getUserId())
                .provider("google")
                .externalAccountId("account-" + UUID.randomUUID())
                .refreshTokenEnc(new byte[] {1})
                .connectedAt(Instant.now())
                .build());
    }

    private GoogleCalendarSyncEvent event(String id, String status, long offsetSeconds) {
        Instant start = Instant.parse("2026-08-20T01:00:00Z").plusSeconds(offsetSeconds);
        return new GoogleCalendarSyncEvent(
                id, status, "never-persist-this-title", new GoogleEventDateTime(start),
                new GoogleEventDateTime(start.plusSeconds(3600)));
    }

    private GoogleCalendarSyncEvent cancellationTombstone(String id) {
        return new GoogleCalendarSyncEvent(id, "cancelled", null, null, null);
    }
}
