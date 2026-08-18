package com.hq.backend.wellness;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.wellness.dto.DailySummaryResponse;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/summary/daily")
@RequiredArgsConstructor
public class DailySummaryController {

    private final DailySummaryService dailySummaryService;

    @GetMapping
    public DailySummaryResponse get(
            @CurrentUserId UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dailySummaryService.getOrGenerate(userId, date);
    }

    @PostMapping("/{summaryId}/viewed")
    public DailySummaryResponse markViewed(@CurrentUserId UUID userId, @PathVariable UUID summaryId) {
        return dailySummaryService.markViewed(userId, summaryId);
    }
}
