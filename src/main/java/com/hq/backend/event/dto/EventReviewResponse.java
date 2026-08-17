package com.hq.backend.event.dto;

import com.hq.backend.event.LocationState;
import java.util.UUID;

public record EventReviewResponse(UUID eventId, LocationState locationState, boolean reviewClosed) {
}
