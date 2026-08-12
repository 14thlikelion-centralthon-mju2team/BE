package com.hq.backend.state.dto;

import java.util.List;

public record StatesResponse(
        List<DailyStateResponse> days
) {
}
