package com.hq.backend.calendar.dto;

import java.time.Instant;

// 해지(revokedAt)된 연결은 connected=false로 내려간다 — FE는 connected만 보고
// 수동 동기화를 걸지 말지 정한다.
public record CalendarStatusResponse(
        boolean connected, String provider, String externalAccountId, Instant connectedAt) {

    public static CalendarStatusResponse disconnected() {
        return new CalendarStatusResponse(false, null, null, null);
    }
}
