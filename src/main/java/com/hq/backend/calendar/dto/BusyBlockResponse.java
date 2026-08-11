package com.hq.backend.calendar.dto;

import java.time.Instant;

public record BusyBlockResponse(Instant startsAt, Instant endsAt, String source) {
}
