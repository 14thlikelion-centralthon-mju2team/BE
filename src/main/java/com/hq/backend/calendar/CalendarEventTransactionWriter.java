package com.hq.backend.calendar;

import com.hq.backend.calendar.dto.GoogleCalendarSyncEvent;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import java.time.Instant;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CalendarEventTransactionWriter {

    private final CalendarSourceRepository sourceRepository;
    private final EventRepository eventRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CalendarUpsertResult upsertOnce(
            UUID userId, UUID connectionId, GoogleCalendarSyncEvent externalEvent) {
        Optional<CalendarSource> existingSource = sourceRepository
                .findByCalendarConnectionIdAndIsDefaultTrueAndDeletedAtIsNull(connectionId);
        if ("cancelled".equals(externalEvent.status()) && existingSource.isEmpty()) {
            throw new CalendarEventNotFoundForCancellationException();
        }
        CalendarSource source = existingSource.orElseGet(() -> ensureDefaultSource(connectionId));
        Optional<Event> existing = eventRepository.findByCalendarSourceIdAndExternalEventIdForUpdate(
                source.getCalendarSourceId(), externalEvent.id());

        if ("cancelled".equals(externalEvent.status())) {
            if (existing.isEmpty()) {
                throw new CalendarEventNotFoundForCancellationException();
            }
            Event event = existing.get();
            if ("cancelled".equals(event.getStatus())) {
                return new CalendarUpsertResult(event.getEventId(), CalendarChangeType.UNCHANGED, false);
            }
            event.setStatus("cancelled");
            event.setUpdatedAt(Instant.now());
            eventRepository.saveAndFlush(event);
            return new CalendarUpsertResult(event.getEventId(), CalendarChangeType.CANCELLED, false);
        }

        Instant startsAt = externalEvent.start().dateTime();
        Instant endsAt = externalEvent.end() == null || externalEvent.end().dateTime() == null
                ? startsAt.plusSeconds(3600) : externalEvent.end().dateTime();
        if (existing.isPresent()) {
            Event event = existing.get();
            boolean changed = !startsAt.equals(event.getStartsAt()) || !endsAt.equals(event.getEndsAt());
            if (!changed) {
                return new CalendarUpsertResult(event.getEventId(), CalendarChangeType.UNCHANGED, false);
            }
            event.setStartsAt(startsAt);
            event.setEndsAt(endsAt);
            event.setUpdatedAt(Instant.now());
            eventRepository.saveAndFlush(event);
            return new CalendarUpsertResult(event.getEventId(), CalendarChangeType.UPDATED, true);
        }

        Event created;
        try {
            created = eventRepository.saveAndFlush(Event.builder()
                    .userId(userId).calendarSourceId(source.getCalendarSourceId()).externalEventId(externalEvent.id())
                    .sourceType("external").startsAt(startsAt).endsAt(endsAt).isAllDay(false)
                    .locationState("undecided").autoManageExcluded(false).excludedFromLearning(false)
                    .status("planned").createdAt(Instant.now()).updatedAt(Instant.now()).build());
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            if (isExternalEventUniqueConflict(exception)) {
                throw new ExternalEventInsertConflictException(exception);
            }
            throw exception;
        }
        return new CalendarUpsertResult(
                created.getEventId(), CalendarChangeType.CREATED, false, created.getRevision());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<CalendarUpsertResult> findExistingAfterConflict(UUID connectionId, String externalEventId) {
        return sourceRepository.findByCalendarConnectionIdAndIsDefaultTrueAndDeletedAtIsNull(connectionId)
                .flatMap(source -> eventRepository.findByCalendarSourceIdAndExternalEventId(
                        source.getCalendarSourceId(), externalEventId))
                .map(event -> new CalendarUpsertResult(event.getEventId(), CalendarChangeType.UNCHANGED, false));
    }

    private CalendarSource ensureDefaultSource(UUID connectionId) {
        sourceRepository.insertDefaultSourceIfAbsent(connectionId, "primary", "내 캘린더");
        return sourceRepository.findByCalendarConnectionIdAndIsDefaultTrueAndDeletedAtIsNull(connectionId)
                .orElseThrow();
    }

    private boolean isExternalEventUniqueConflict(org.springframework.dao.DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())
                    && sqlException.getMessage() != null
                    && sqlException.getMessage().contains("uq_event_external")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static final class CalendarEventNotFoundForCancellationException extends RuntimeException {
    }

    static final class ExternalEventInsertConflictException extends RuntimeException {
        ExternalEventInsertConflictException(org.springframework.dao.DataIntegrityViolationException cause) {
            super(cause);
        }

        @Override
        public org.springframework.dao.DataIntegrityViolationException getCause() {
            return (org.springframework.dao.DataIntegrityViolationException) super.getCause();
        }
    }
}
