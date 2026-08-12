package com.hq.backend.state.dto;

import com.hq.backend.state.State;
import java.time.LocalDate;
import java.util.List;

public record DailyStateResponse(
        LocalDate date,
        State state,
        String confidence,
        List<String> reasons,
        boolean performed
) {
}
