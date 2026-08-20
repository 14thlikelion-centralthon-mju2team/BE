package com.hq.backend.calendar;

import com.hq.backend.calendar.dto.GoogleCalendarSyncEvent;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CalendarEventWriter {

    private final CalendarEventTransactionWriter transactionWriter;

    public Optional<CalendarUpsertResult> upsert(
            UUID userId, UUID connectionId, GoogleCalendarSyncEvent externalEvent) {
        if (externalEvent.id() == null) {
            return Optional.empty();
        }
        if (!"cancelled".equals(externalEvent.status())
                && (externalEvent.start() == null || externalEvent.start().dateTime() == null)) {
            return Optional.empty();
        }
        try {
            return Optional.of(transactionWriter.upsertOnce(userId, connectionId, externalEvent));
        } catch (CalendarEventTransactionWriter.CalendarEventNotFoundForCancellationException exception) {
            return Optional.empty();
        } catch (CalendarEventTransactionWriter.ExternalEventInsertConflictException exception) {
            Optional<CalendarUpsertResult> existing =
                    transactionWriter.findExistingAfterConflict(connectionId, externalEvent.id());
            if (existing.isPresent()) {
                return existing;
            }
            throw exception.getCause();
        }
    }
}
