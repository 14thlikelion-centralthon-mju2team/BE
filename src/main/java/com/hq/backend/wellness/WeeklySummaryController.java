package com.hq.backend.wellness;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.wellness.dto.WeeklySummaryResponse;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 주간 리포트(CAL-06). date는 주를 특정하는 아무 날짜여도 되고, 서버가 그 날이 속한
// KST 주(월~일)로 맞춘다 — 클라이언트가 주 경계를 직접 계산하지 않게 한다.
@RestController
@RequiredArgsConstructor
public class WeeklySummaryController {

    private final WeeklySummaryService weeklySummaryService;

    @GetMapping("/summary/weekly")
    public WeeklySummaryResponse get(
            @CurrentUserId UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return weeklySummaryService.get(userId, date);
    }
}
