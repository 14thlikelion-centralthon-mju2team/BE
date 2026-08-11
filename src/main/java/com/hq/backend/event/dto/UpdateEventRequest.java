package com.hq.backend.event.dto;

import java.time.Instant;

// PATCH 부분수정: null인 필드는 그대로 두고, 값이 있는 필드만 반영한다.
public record UpdateEventRequest(String title, Instant startsAt, Instant endsAt, String placeText) {
}
