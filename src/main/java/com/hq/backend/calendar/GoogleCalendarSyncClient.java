package com.hq.backend.calendar;

import java.time.Instant;
import java.util.Optional;

public interface GoogleCalendarSyncClient {

    Optional<GoogleSyncBatch> fetchAll(String accessToken, String syncToken, Instant now)
            throws GoogleSyncTokenExpiredException;
}
