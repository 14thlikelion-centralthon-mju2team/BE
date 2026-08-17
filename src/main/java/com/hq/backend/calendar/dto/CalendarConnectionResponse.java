package com.hq.backend.calendar.dto;

import java.time.Instant;

public record CalendarConnectionResponse(String provider, String externalAccountId, Instant connectedAt) {
}
