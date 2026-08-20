package com.hq.backend.calendar;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CalendarSyncStateWriter {

    private final CalendarConnectionRepository connectionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean advanceSyncToken(UUID connectionId, String expectedOldToken, String nextToken) {
        if (nextToken == null) return false;
        int updated = expectedOldToken == null
                ? connectionRepository.setInitialSyncTokenIfAbsent(connectionId, nextToken)
                : connectionRepository.replaceSyncToken(connectionId, expectedOldToken, nextToken);
        return updated == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean clearExpiredSyncToken(UUID connectionId, String expectedOldToken) {
        return expectedOldToken != null && connectionRepository.clearSyncToken(connectionId, expectedOldToken) == 1;
    }
}
