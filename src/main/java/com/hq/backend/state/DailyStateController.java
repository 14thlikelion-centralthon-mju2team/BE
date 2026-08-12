package com.hq.backend.state;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.state.dto.StatesResponse;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DailyStateController {

    private final DailyStateService dailyStateService;

    @GetMapping("/states")
    public StatesResponse getStates(
            @CurrentUserId UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dailyStateService.getStates(userId, from, to);
    }
}
